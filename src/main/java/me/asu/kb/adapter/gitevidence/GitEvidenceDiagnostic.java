package me.asu.kb.adapter.gitevidence;

import java.util.Objects;

/** A safe structured explanation of a Git evidence outcome. */
public record GitEvidenceDiagnostic(GitEvidenceDiagnosticCode code, String detail) {

  public GitEvidenceDiagnostic {
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }
}
