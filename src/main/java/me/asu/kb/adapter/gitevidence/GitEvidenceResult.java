package me.asu.kb.adapter.gitevidence;

import java.util.List;
import java.util.Objects;

/** The complete or explicitly incomplete outcome of one local Git inspection. */
public record GitEvidenceResult(
    List<GitCommitEvidence> commits,
    GitToolProfile profile,
    List<GitEvidenceDiagnostic> diagnostics,
    boolean complete) {

  public GitEvidenceResult {
    commits = commits == null ? List.of() : List.copyOf(commits);
    profile = Objects.requireNonNull(profile, "profile");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  /** True only for a fully completed inspection without warnings or failures. */
  public boolean successful() {
    return complete && diagnostics.isEmpty();
  }
}
