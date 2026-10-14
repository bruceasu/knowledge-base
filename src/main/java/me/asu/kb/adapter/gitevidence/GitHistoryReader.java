package me.asu.kb.adapter.gitevidence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads bounded commit metadata and path-level facts through machine-readable Git formats. */
final class GitHistoryReader {
  private static final String METADATA_FORMAT =
      "%H%x00%P%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%s%x00";
  private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  private static final int MAX_NAME_BYTES = 256;
  private static final int MAX_EMAIL_BYTES = 320;
  private static final int MAX_SUBJECT_BYTES = 1_024;

  private final GitCommandRunner runner;

  GitHistoryReader(GitEvidenceSettings settings) {
    this(settings, new ProcessBuilderGitLauncher());
  }

  GitHistoryReader(GitEvidenceSettings settings, GitProcessLauncher launcher) {
    runner = new GitCommandRunner(settings.executable(), launcher);
  }

  RawGitCommit readCommit(
      Path canonicalGitRoot,
      String objectId,
      List<String> repositoryRelativeFilters,
      GitExecutionBudget budget)
      throws GitProbeException {
    RawMetadata metadata = readMetadata(canonicalGitRoot, objectId, budget);
    List<RawStatus> statuses =
        readStatuses(
            canonicalGitRoot,
            objectId,
            metadata.parentObjectIds(),
            repositoryRelativeFilters,
            budget);
    Map<ChangeKey, Boolean> binaryByChange =
        readBinaryFacts(
            canonicalGitRoot,
            objectId,
            metadata.parentObjectIds(),
            repositoryRelativeFilters,
            budget);
    List<RawGitFileChange> changes = new ArrayList<>(statuses.size());
    for (RawStatus status : statuses) {
      ChangeKey key = ChangeKey.fromStatus(status);
      Boolean binary = binaryByChange.remove(key);
      if (binary == null) {
        throw malformed("Git name-status and numstat outputs do not describe the same changes");
      }
      changes.add(new RawGitFileChange(status.kind(), status.oldPath(), status.newPath(), binary));
    }
    if (!binaryByChange.isEmpty()) {
      throw malformed("Git numstat output contains an unmatched change");
    }
    return new RawGitCommit(
        metadata.objectId(),
        metadata.parentObjectIds(),
        metadata.authorName(),
        metadata.authorEmail(),
        metadata.authoredAt(),
        metadata.committerName(),
        metadata.committerEmail(),
        metadata.committedAt(),
        metadata.subject(),
        changes);
  }

  private RawMetadata readMetadata(
      Path gitRoot, String requestedObjectId, GitExecutionBudget budget) throws GitProbeException {
    GitCommandOutput output =
        runner.execute(
            gitRoot,
            List.of(
                "show",
                "-s",
                "--no-show-signature",
                "--no-use-mailmap",
                "--format=format:" + METADATA_FORMAT,
                requestedObjectId),
            budget);
    requireSuccessful(output, "Git commit metadata query failed");
    List<String> fields = nulFields(output.strictUtf8());
    if (fields.size() != 9 || !requestedObjectId.equals(fields.get(0))) {
      throw malformed("Git commit metadata output is malformed");
    }
    List<String> parents = fields.get(1).isEmpty() ? List.of() : List.of(fields.get(1).split(" "));
    for (String parent : parents) {
      if (!OBJECT_ID.matcher(parent).matches()) {
        throw malformed("Git commit metadata contains an invalid parent object ID");
      }
    }
    try {
      return new RawMetadata(
          fields.get(0),
          parents,
          truncateUtf8(fields.get(2), MAX_NAME_BYTES),
          truncateUtf8(fields.get(3), MAX_EMAIL_BYTES),
          Instant.parse(fields.get(4)),
          truncateUtf8(fields.get(5), MAX_NAME_BYTES),
          truncateUtf8(fields.get(6), MAX_EMAIL_BYTES),
          Instant.parse(fields.get(7)),
          truncateUtf8(fields.get(8), MAX_SUBJECT_BYTES));
    } catch (DateTimeException error) {
      throw malformed("Git commit metadata contains an invalid timestamp");
    }
  }

  private List<RawStatus> readStatuses(
      Path gitRoot,
      String objectId,
      List<String> parents,
      List<String> repositoryRelativeFilters,
      GitExecutionBudget budget)
      throws GitProbeException {
    List<String> arguments = diffArguments("--name-status", objectId, parents, repositoryRelativeFilters);
    GitCommandOutput output = runner.execute(gitRoot, arguments, budget);
    requireSuccessful(output, "Git name-status query failed");
    List<String> fields = nulFields(output.strictUtf8());
    List<RawStatus> statuses = new ArrayList<>();
    for (int index = 0; index < fields.size(); ) {
      String status = fields.get(index++);
      if (status.isEmpty()) {
        throw malformed("Git name-status output contains an empty status");
      }
      char code = status.charAt(0);
      if (code == 'R' || code == 'C') {
        if (!status.substring(1).matches("\\d{1,3}") || index + 1 >= fields.size()) {
          throw malformed("Git rename/copy status output is malformed");
        }
        statuses.add(
            new RawStatus(
                code == 'R' ? GitChangeKind.RENAME : GitChangeKind.COPY,
                Optional.of(requirePath(fields.get(index++))),
                Optional.of(requirePath(fields.get(index++)))));
      } else {
        if (status.length() != 1 || index >= fields.size()) {
          throw malformed("Git name-status output is malformed");
        }
        String path = requirePath(fields.get(index++));
        GitChangeKind kind =
            switch (code) {
              case 'A' -> GitChangeKind.ADD;
              case 'M' -> GitChangeKind.MODIFY;
              case 'D' -> GitChangeKind.DELETE;
              default -> throw malformed("Git emitted an unsupported path change kind");
            };
        statuses.add(
            switch (kind) {
              case ADD -> new RawStatus(kind, Optional.empty(), Optional.of(path));
              case DELETE -> new RawStatus(kind, Optional.of(path), Optional.empty());
              default -> new RawStatus(kind, Optional.of(path), Optional.of(path));
            });
      }
    }
    return statuses;
  }

  private Map<ChangeKey, Boolean> readBinaryFacts(
      Path gitRoot,
      String objectId,
      List<String> parents,
      List<String> repositoryRelativeFilters,
      GitExecutionBudget budget)
      throws GitProbeException {
    List<String> arguments = diffArguments("--numstat", objectId, parents, repositoryRelativeFilters);
    GitCommandOutput output = runner.execute(gitRoot, arguments, budget);
    requireSuccessful(output, "Git numstat query failed");
    List<String> fields = nulFields(output.strictUtf8());
    Map<ChangeKey, Boolean> result = new LinkedHashMap<>();
    for (int index = 0; index < fields.size(); ) {
      String header = fields.get(index++);
      int firstTab = header.indexOf('\t');
      int secondTab = firstTab < 0 ? -1 : header.indexOf('\t', firstTab + 1);
      if (firstTab <= 0 || secondTab <= firstTab + 1) {
        throw malformed("Git numstat output is malformed");
      }
      String additions = header.substring(0, firstTab);
      String deletions = header.substring(firstTab + 1, secondTab);
      String path = header.substring(secondTab + 1);
      boolean binary = additions.equals("-") || deletions.equals("-");
      if (!binary && (!digits(additions) || !digits(deletions))) {
        throw malformed("Git numstat counts are malformed");
      }
      ChangeKey key;
      if (path.isEmpty()) {
        if (index + 1 >= fields.size()) {
          throw malformed("Git rename/copy numstat output is malformed");
        }
        key =
            new ChangeKey(
                Optional.of(requirePath(fields.get(index++))),
                Optional.of(requirePath(fields.get(index++))));
      } else {
        String requiredPath = requirePath(path);
        key = new ChangeKey(Optional.of(requiredPath), Optional.of(requiredPath));
      }
      if (result.putIfAbsent(key, binary) != null) {
        throw malformed("Git numstat output contains a duplicate change");
      }
    }
    return result;
  }

  private static List<String> diffArguments(
      String format,
      String objectId,
      List<String> parents,
      List<String> repositoryRelativeFilters) {
    List<String> arguments = new ArrayList<>();
    arguments.add("diff-tree");
    arguments.add("--no-commit-id");
    arguments.add("-r");
    arguments.add("-z");
    arguments.add(format);
    arguments.add("-M");
    arguments.add("-C");
    if (parents.isEmpty()) {
      arguments.add("--root");
      arguments.add(objectId);
    } else {
      arguments.add(parents.get(0));
      arguments.add(objectId);
    }
    if (!repositoryRelativeFilters.isEmpty()) {
      arguments.add("--");
      arguments.addAll(repositoryRelativeFilters);
    }
    return List.copyOf(arguments);
  }

  private static List<String> nulFields(String output) throws GitProbeException {
    if (output.isEmpty()) {
      return List.of();
    }
    if (output.charAt(output.length() - 1) != '\0') {
      throw malformed("Git machine output is not NUL-terminated");
    }
    List<String> fields = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < output.length(); index++) {
      if (output.charAt(index) == '\0') {
        fields.add(output.substring(start, index));
        start = index + 1;
      }
    }
    return fields;
  }

  private static String requirePath(String path) throws GitProbeException {
    if (path.isEmpty() || path.indexOf('\0') >= 0) {
      throw malformed("Git emitted an invalid path");
    }
    return path;
  }

  private static boolean digits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static String truncateUtf8(String value, int maximumBytes) {
    if (value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
      return value;
    }
    int end = value.length();
    while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
      end = value.offsetByCodePoints(end, -1);
    }
    return value.substring(0, end);
  }

  private static void requireSuccessful(GitCommandOutput output, String detail)
      throws GitProbeException {
    if (output.exitCode() != 0) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.UNEXPECTED_EXIT,
          detail + " (exit " + output.exitCode() + ")");
    }
  }

  private static GitProbeException malformed(String detail) {
    return new GitProbeException(GitEvidenceDiagnosticCode.MALFORMED_OUTPUT, detail);
  }

  private record RawMetadata(
      String objectId,
      List<String> parentObjectIds,
      String authorName,
      String authorEmail,
      Instant authoredAt,
      String committerName,
      String committerEmail,
      Instant committedAt,
      String subject) {
    private RawMetadata {
      parentObjectIds = List.copyOf(parentObjectIds);
    }
  }

  private record RawStatus(
      GitChangeKind kind, Optional<String> oldPath, Optional<String> newPath) {}

  private record ChangeKey(Optional<String> oldPath, Optional<String> newPath) {
    private static ChangeKey fromStatus(RawStatus status) {
      if (status.oldPath().isPresent()
          && status.newPath().isPresent()
          && !status.oldPath().equals(status.newPath())) {
        return new ChangeKey(status.oldPath(), status.newPath());
      }
      Optional<String> path = status.newPath().isPresent() ? status.newPath() : status.oldPath();
      return new ChangeKey(path, path);
    }
  }
}
