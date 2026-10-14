package me.asu.kb.adapter.gitevidence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.SourceRef;

/** Projects repository-relative Git facts into workspace-confined evidence candidates. */
final class GitEvidenceProjector {

  ProjectedCommit project(ValidatedGitRequest request, RawGitCommit rawCommit)
      throws GitProbeException {
    String workspacePrefix = portable(request.repository().gitRoot().relativize(request.workspaceRoot()));
    List<GitFileChange> projectedChanges = new ArrayList<>();
    List<GitEvidenceDiagnostic> diagnostics = new ArrayList<>();
    for (RawGitFileChange rawChange : rawCommit.changes()) {
      Optional<String> oldPath =
          projectEndpoint(rawChange.oldRepositoryPath(), workspacePrefix);
      Optional<String> newPath =
          projectEndpoint(rawChange.newRepositoryPath(), workspacePrefix);
      if ((rawChange.oldRepositoryPath().isPresent() && oldPath.isEmpty())
          || (rawChange.newRepositoryPath().isPresent() && newPath.isEmpty())) {
        diagnostics.add(
            new GitEvidenceDiagnostic(
                GitEvidenceDiagnosticCode.OUT_OF_WORKSPACE_CHANGE,
                "Git change has an endpoint outside the registered workspace"));
        continue;
      }
      String evidencePath = newPath.orElseGet(oldPath::orElseThrow);
      Evidence evidence =
          new Evidence(
              EvidenceType.VERSION_CONTROL,
              new SourceRef(
                  "engineering-workspace",
                  request.workspaceId() + ":" + evidencePath,
                  Optional.of(rawCommit.objectId())),
              "commit:" + rawCommit.objectId() + ":" + rawChange.kind().name() + ":" + evidencePath,
              1.0d);
      try {
        projectedChanges.add(
            new GitFileChange(
                rawChange.kind(), oldPath, newPath, rawChange.binary(), evidence));
      } catch (IllegalArgumentException error) {
        throw malformed("Git change endpoints do not match the reported change kind");
      }
    }

    Evidence commitEvidence =
        new Evidence(
            EvidenceType.VERSION_CONTROL,
            new SourceRef(
                "engineering-workspace",
                request.workspaceId(),
                Optional.of(rawCommit.objectId())),
            "commit:" + rawCommit.objectId(),
            1.0d);
    GitCommitEvidence commit =
        new GitCommitEvidence(
            rawCommit.objectId(),
            rawCommit.parentObjectIds(),
            rawCommit.authorName(),
            rawCommit.authorEmail(),
            rawCommit.authoredAt(),
            rawCommit.committerName(),
            rawCommit.committerEmail(),
            rawCommit.committedAt(),
            rawCommit.subject(),
            projectedChanges,
            commitEvidence);
    return new ProjectedCommit(commit, diagnostics);
  }

  private static Optional<String> projectEndpoint(
      Optional<String> repositoryPath, String workspacePrefix) throws GitProbeException {
    if (repositoryPath.isEmpty()) {
      return Optional.empty();
    }
    String normalized = normalizedRepositoryPath(repositoryPath.orElseThrow());
    if (workspacePrefix.isEmpty()) {
      return Optional.of(normalized);
    }
    String requiredPrefix = workspacePrefix + "/";
    if (!normalized.startsWith(requiredPrefix)) {
      return Optional.empty();
    }
    return Optional.of(normalized.substring(requiredPrefix.length()));
  }

  private static String normalizedRepositoryPath(String path) throws GitProbeException {
    if (path.isEmpty()
        || path.startsWith("/")
        || path.endsWith("/")
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0) {
      throw malformed("Git emitted a non-normalized repository path");
    }
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw malformed("Git emitted a repository path with an unsafe segment");
      }
    }
    return String.join("/", segments);
  }

  private static String portable(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static GitProbeException malformed(String detail) {
    return new GitProbeException(GitEvidenceDiagnosticCode.MALFORMED_OUTPUT, detail);
  }

  record ProjectedCommit(
      GitCommitEvidence commit, List<GitEvidenceDiagnostic> diagnostics) {
    ProjectedCommit {
      diagnostics = List.copyOf(diagnostics);
    }
  }
}
