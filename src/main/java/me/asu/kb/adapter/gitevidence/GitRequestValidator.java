package me.asu.kb.adapter.gitevidence;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts caller values into a workspace-confined, commit-only inspection plan. */
final class GitRequestValidator {
  private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_PATH_FILTERS = 64;

  private final Map<String, Path> workspaceRoots;
  private final int defaultCommitLimit;
  private final int defaultChangeLimit;

  GitRequestValidator(
      Map<String, Path> workspaceRoots, int defaultCommitLimit, int defaultChangeLimit) {
    if (defaultCommitLimit < 1 || defaultCommitLimit > 256) {
      throw new IllegalArgumentException("defaultCommitLimit must be between 1 and 256");
    }
    if (defaultChangeLimit < 1 || defaultChangeLimit > 10_000) {
      throw new IllegalArgumentException("defaultChangeLimit must be between 1 and 10000");
    }
    this.workspaceRoots = canonicalWorkspaceRoots(workspaceRoots);
    this.defaultCommitLimit = defaultCommitLimit;
    this.defaultChangeLimit = defaultChangeLimit;
  }

  ValidatedGitRequest validate(
      GitEvidenceRequest request, GitRepositoryProbe probe, GitExecutionBudget budget)
      throws GitValidationException, GitProbeException {
    Objects.requireNonNull(probe, "probe");
    Objects.requireNonNull(budget, "budget");
    if (request == null) {
      throw invalid(GitEvidenceDiagnosticCode.INVALID_SELECTION, "request must not be null");
    }
    Path workspace = workspaceRoots.get(request.workspaceId());
    if (workspace == null) {
      throw invalid(GitEvidenceDiagnosticCode.UNKNOWN_WORKSPACE, "workspace is not registered");
    }
    int commitLimit = requestBudget(request.maxCommits(), defaultCommitLimit, 256, "commit");
    int changeLimit = requestBudget(request.maxChanges(), defaultChangeLimit, 10_000, "change");
    List<String> rawObjectIds = rawObjectIds(request.commitSelection());
    if (request.commitSelection() instanceof ExplicitCommits
        && rawObjectIds.size() > commitLimit) {
      throw invalid(
          GitEvidenceDiagnosticCode.REQUEST_LIMIT_EXCEEDED,
          "explicit commit selection exceeds the request budget");
    }
    validateGenericObjectIdGrammar(rawObjectIds);
    List<String> workspaceFilters = validatePathFilters(request.relativePathFilters(), workspace);

    GitRepositoryFacts facts = probe.repositoryFacts(workspace, budget);
    Path gitRoot = canonical(facts.gitRoot(), GitEvidenceDiagnosticCode.NOT_GIT_REPOSITORY);
    if (!workspace.startsWith(gitRoot)) {
      throw invalid(
          GitEvidenceDiagnosticCode.REPOSITORY_BOUNDARY_MISMATCH,
          "Git root does not contain the registered workspace");
    }
    validateObjectFormat(rawObjectIds, facts.objectFormat());
    for (String objectId : rawObjectIds) {
      GitObjectStatus status = probe.objectStatus(gitRoot, objectId, budget);
      if (status == GitObjectStatus.MISSING) {
        throw invalid(GitEvidenceDiagnosticCode.MISSING_OBJECT, "commit object is unavailable");
      }
      if (status != GitObjectStatus.COMMIT) {
        throw invalid(GitEvidenceDiagnosticCode.NON_COMMIT_OBJECT, "selected object is not a commit");
      }
    }

    ResolvedSelection selection =
        resolveSelection(
            request.commitSelection(), rawObjectIds, facts, gitRoot, probe, commitLimit, budget);
    Path workspacePrefix = gitRoot.relativize(workspace);
    String portableWorkspacePrefix = toPortablePath(workspacePrefix);
    List<String> repositoryFilters = new ArrayList<>(workspaceFilters.size());
    if (workspaceFilters.isEmpty() && !portableWorkspacePrefix.isEmpty()) {
      repositoryFilters.add(portableWorkspacePrefix);
    } else {
      for (String filter : workspaceFilters) {
        Path translated = workspacePrefix.resolve(fromPortablePath(filter)).normalize();
        repositoryFilters.add(toPortablePath(translated));
      }
    }
    return new ValidatedGitRequest(
        request.workspaceId(),
        workspace,
        new GitRepositoryFacts(
            gitRoot, facts.objectFormat(), facts.shallowRepository(), facts.effectiveVersion()),
        selection.objectIds(),
        workspaceFilters,
        repositoryFilters,
        commitLimit,
        changeLimit,
        selection.truncated());
  }

  private static ResolvedSelection resolveSelection(
      GitCommitSelection requested,
      List<String> rawObjectIds,
      GitRepositoryFacts facts,
      Path gitRoot,
      GitRepositoryProbe probe,
      int commitLimit,
      GitExecutionBudget budget)
      throws GitValidationException, GitProbeException {
    if (requested instanceof ExplicitCommits) {
      return new ResolvedSelection(rawObjectIds, false);
    }
    InclusiveCommitRange range = (InclusiveCommitRange) requested;
    if (!probe.isAncestor(
        gitRoot, range.oldestInclusive(), range.newestInclusive(), budget)) {
      GitEvidenceDiagnosticCode code =
          facts.shallowRepository()
              ? GitEvidenceDiagnosticCode.SHALLOW_HISTORY
              : GitEvidenceDiagnosticCode.INVALID_SELECTION;
      throw invalid(code, "oldest commit is not a reachable ancestor of newest commit");
    }
    List<String> resolved =
        probe.commitsInInclusiveRange(
            gitRoot,
            range.oldestInclusive(),
            range.newestInclusive(),
            commitLimit + 1,
            budget);
    boolean truncated = resolved.size() > commitLimit;
    if (truncated) {
      resolved = new ArrayList<>(resolved.subList(0, commitLimit));
    }
    return new ResolvedSelection(resolved, truncated);
  }

  private static List<String> rawObjectIds(GitCommitSelection selection)
      throws GitValidationException {
    if (selection instanceof ExplicitCommits explicit) {
      if (explicit.objectIds().isEmpty()) {
        throw invalid(GitEvidenceDiagnosticCode.INVALID_SELECTION, "commit selection is empty");
      }
      Set<String> unique = new LinkedHashSet<>(explicit.objectIds());
      if (unique.size() != explicit.objectIds().size()) {
        throw invalid(
            GitEvidenceDiagnosticCode.INVALID_SELECTION,
            "explicit commit selection contains duplicates");
      }
      return List.copyOf(unique);
    }
    if (selection instanceof InclusiveCommitRange range) {
      if (range.oldestInclusive() == null || range.newestInclusive() == null) {
        throw invalid(
            GitEvidenceDiagnosticCode.INVALID_SELECTION, "commit range endpoints are required");
      }
      return List.of(range.oldestInclusive(), range.newestInclusive());
    }
    throw invalid(GitEvidenceDiagnosticCode.INVALID_SELECTION, "commit selection is required");
  }

  private static void validateGenericObjectIdGrammar(List<String> objectIds)
      throws GitValidationException {
    for (String objectId : objectIds) {
      if (objectId == null || (!SHA1.matcher(objectId).matches() && !SHA256.matcher(objectId).matches())) {
        throw invalid(
            GitEvidenceDiagnosticCode.INVALID_SELECTION,
            "commit IDs must be complete lowercase hexadecimal object IDs");
      }
    }
  }

  private static void validateObjectFormat(List<String> objectIds, String objectFormat)
      throws GitValidationException {
    Pattern expected =
        switch (objectFormat.toLowerCase(Locale.ROOT)) {
          case "sha1" -> SHA1;
          case "sha256" -> SHA256;
          default ->
              throw invalid(
                  GitEvidenceDiagnosticCode.INCOMPATIBLE_PROFILE,
                  "repository object format is unsupported");
        };
    for (String objectId : objectIds) {
      if (!expected.matcher(objectId).matches()) {
        throw invalid(
            GitEvidenceDiagnosticCode.INVALID_SELECTION,
            "commit ID length does not match the repository object format");
      }
    }
  }

  private static List<String> validatePathFilters(List<String> filters, Path workspace)
      throws GitValidationException {
    if (filters.size() > MAX_PATH_FILTERS) {
      throw invalid(
          GitEvidenceDiagnosticCode.REQUEST_LIMIT_EXCEEDED,
          "path filters exceed the configured limit");
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String raw : filters) {
      if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) {
        throw invalid(GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE, "path filter is invalid");
      }
      final Path candidate;
      try {
        candidate = Path.of(raw);
      } catch (InvalidPathException error) {
        throw invalid(GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE, "path filter is invalid");
      }
      if (candidate.isAbsolute()) {
        throw invalid(GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE, "path filter must be relative");
      }
      for (Path segment : candidate) {
        String value = segment.toString();
        if (value.equals(".") || value.equals("..")) {
          throw invalid(
              GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE,
              "path filter cannot contain dot or parent segments");
        }
      }
      Path resolved = workspace.resolve(candidate).normalize();
      if (!resolved.startsWith(workspace) || resolved.equals(workspace)) {
        throw invalid(
            GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE,
            "path filter escapes or names the workspace root");
      }
      normalized.add(toPortablePath(workspace.relativize(resolved)));
    }
    return List.copyOf(normalized);
  }

  private static int requestBudget(int requested, int configured, int hardMaximum, String name)
      throws GitValidationException {
    if (requested < 0 || requested > hardMaximum) {
      throw invalid(
          GitEvidenceDiagnosticCode.REQUEST_LIMIT_EXCEEDED,
          name + " budget is outside the supported range");
    }
    return requested == 0 ? configured : requested;
  }

  private static Map<String, Path> canonicalWorkspaceRoots(Map<String, Path> roots) {
    Objects.requireNonNull(roots, "workspaceRoots");
    Map<String, Path> canonical = new LinkedHashMap<>();
    for (Map.Entry<String, Path> entry : roots.entrySet()) {
      String id = entry.getKey();
      if (id == null || id.isBlank() || canonical.containsKey(id)) {
        throw new IllegalArgumentException("workspace ID must be unique and nonblank");
      }
      canonical.put(id, canonical(entry.getValue(), GitEvidenceDiagnosticCode.UNKNOWN_WORKSPACE));
    }
    return Collections.unmodifiableMap(canonical);
  }

  private static Path canonical(Path path, GitEvidenceDiagnosticCode code) {
    Objects.requireNonNull(path, "path");
    try {
      return path.toAbsolutePath().normalize().toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(code + ": path cannot be resolved", error);
    }
  }

  private static Path fromPortablePath(String path) {
    return Path.of(path.replace('/', java.io.File.separatorChar));
  }

  private static String toPortablePath(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static GitValidationException invalid(GitEvidenceDiagnosticCode code, String detail) {
    return new GitValidationException(code, detail);
  }

  private record ResolvedSelection(List<String> objectIds, boolean truncated) {
    private ResolvedSelection {
      objectIds = List.copyOf(objectIds);
    }
  }
}
