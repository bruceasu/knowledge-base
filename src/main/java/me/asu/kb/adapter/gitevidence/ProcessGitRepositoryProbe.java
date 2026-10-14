package me.asu.kb.adapter.gitevidence;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed-profile implementation of the repository validation probe. */
final class ProcessGitRepositoryProbe implements GitRepositoryProbe {
  private static final Pattern VERSION =
      Pattern.compile("git version (\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");
  private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  private final GitCommandRunner runner;

  ProcessGitRepositoryProbe(GitEvidenceSettings settings) {
    this(settings, new ProcessBuilderGitLauncher());
  }

  ProcessGitRepositoryProbe(GitEvidenceSettings settings, GitProcessLauncher launcher) {
    runner = new GitCommandRunner(settings.executable(), launcher);
  }

  @Override
  public GitRepositoryFacts repositoryFacts(
      Path canonicalWorkspace, GitExecutionBudget budget) throws GitProbeException {
    GitCommandOutput versionOutput = runner.execute(canonicalWorkspace, List.of("--version"), budget);
    requireExit(versionOutput, 0, GitEvidenceDiagnosticCode.INCOMPATIBLE_PROFILE);
    String version = oneLine(versionOutput.strictUtf8(), "Git version output is malformed");
    verifyVersion(version);

    GitCommandOutput factsOutput =
        runner.execute(
            canonicalWorkspace,
            List.of(
                "rev-parse",
                "--show-toplevel",
                "--show-object-format",
                "--is-shallow-repository"),
            budget);
    if (factsOutput.exitCode() != 0) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.NOT_GIT_REPOSITORY,
          "workspace is not inside a readable local Git repository");
    }
    List<String> facts = nonTrailingLines(factsOutput.strictUtf8());
    if (facts.size() != 3) {
      throw malformed("Git repository facts are malformed");
    }
    Path gitRoot = canonicalGitRoot(facts.get(0));
    String objectFormat = facts.get(1).toLowerCase(Locale.ROOT);
    if (!objectFormat.equals("sha1") && !objectFormat.equals("sha256")) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.INCOMPATIBLE_PROFILE,
          "repository object format is unsupported");
    }
    boolean shallow =
        switch (facts.get(2)) {
          case "true" -> true;
          case "false" -> false;
          default -> throw malformed("Git shallow-repository output is malformed");
        };
    return new GitRepositoryFacts(gitRoot, objectFormat, shallow, version);
  }

  @Override
  public GitObjectStatus objectStatus(
      Path canonicalGitRoot, String completeObjectId, GitExecutionBudget budget)
      throws GitProbeException {
    GitCommandOutput output =
        runner.execute(canonicalGitRoot, List.of("cat-file", "-t", completeObjectId), budget);
    if (output.exitCode() == 128) {
      return GitObjectStatus.MISSING;
    }
    requireExit(output, 0, GitEvidenceDiagnosticCode.UNEXPECTED_EXIT);
    String type = oneLine(output.strictUtf8(), "Git object type output is malformed");
    return type.equals("commit") ? GitObjectStatus.COMMIT : GitObjectStatus.NON_COMMIT;
  }

  @Override
  public boolean isAncestor(
      Path canonicalGitRoot,
      String oldestObjectId,
      String newestObjectId,
      GitExecutionBudget budget)
      throws GitProbeException {
    GitCommandOutput output =
        runner.execute(
            canonicalGitRoot,
            List.of("merge-base", "--is-ancestor", oldestObjectId, newestObjectId),
            budget);
    if (output.exitCode() == 0) {
      return true;
    }
    if (output.exitCode() == 1) {
      return false;
    }
    throw new GitProbeException(
        GitEvidenceDiagnosticCode.UNEXPECTED_EXIT, "Git ancestry check failed unexpectedly");
  }

  @Override
  public List<String> commitsInInclusiveRange(
      Path canonicalGitRoot,
      String oldestObjectId,
      String newestObjectId,
      int maximumToReturn,
      GitExecutionBudget budget)
      throws GitProbeException {
    if (maximumToReturn < 1) {
      throw new IllegalArgumentException("maximumToReturn must be positive");
    }
    if (oldestObjectId.equals(newestObjectId) || maximumToReturn == 1) {
      return List.of(oldestObjectId);
    }
    GitCommandOutput output =
        runner.execute(
            canonicalGitRoot,
            List.of(
                "rev-list",
                "--ancestry-path",
                "--topo-order",
                "--reverse",
                "--max-count=" + (maximumToReturn - 1),
                oldestObjectId + ".." + newestObjectId),
            budget);
    requireExit(output, 0, GitEvidenceDiagnosticCode.UNEXPECTED_EXIT);
    List<String> objectIds = new ArrayList<>();
    objectIds.add(oldestObjectId);
    objectIds.addAll(nonTrailingLines(output.strictUtf8()));
    Set<String> unique = new LinkedHashSet<>();
    for (String objectId : objectIds) {
      if (!OBJECT_ID.matcher(objectId).matches() || !unique.add(objectId)) {
        throw malformed("Git range output contains an invalid commit object ID");
      }
    }
    return List.copyOf(unique);
  }

  private static void verifyVersion(String version) throws GitProbeException {
    Matcher matcher = VERSION.matcher(version);
    if (!matcher.matches()) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.INCOMPATIBLE_PROFILE, "Git version output is unsupported");
    }
    int major = Integer.parseInt(matcher.group(1));
    int minor = Integer.parseInt(matcher.group(2));
    if (major < 2 || (major == 2 && minor < 30)) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.INCOMPATIBLE_PROFILE, "Git 2.30 or newer is required");
    }
  }

  private static Path canonicalGitRoot(String raw) throws GitProbeException {
    try {
      return Path.of(raw).toAbsolutePath().normalize().toRealPath();
    } catch (InvalidPathException | IOException error) {
      throw malformed("Git top-level path cannot be resolved");
    }
  }

  private static String oneLine(String output, String detail) throws GitProbeException {
    List<String> lines = nonTrailingLines(output);
    if (lines.size() != 1 || lines.get(0).isEmpty()) {
      throw malformed(detail);
    }
    return lines.get(0);
  }

  private static List<String> nonTrailingLines(String output) {
    String normalized = output.replace("\r\n", "\n");
    if (normalized.endsWith("\n")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isEmpty() ? List.of() : List.of(normalized.split("\n", -1));
  }

  private static void requireExit(
      GitCommandOutput output, int expected, GitEvidenceDiagnosticCode code)
      throws GitProbeException {
    if (output.exitCode() != expected) {
      throw new GitProbeException(code, "fixed-profile Git command exited unexpectedly");
    }
  }

  private static GitProbeException malformed(String detail) {
    return new GitProbeException(GitEvidenceDiagnosticCode.MALFORMED_OUTPUT, detail);
  }

}
