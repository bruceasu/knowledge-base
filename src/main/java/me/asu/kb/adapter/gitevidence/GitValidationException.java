package me.asu.kb.adapter.gitevidence;

/** Deterministic request or repository-boundary rejection before history inspection. */
final class GitValidationException extends Exception {
  private final GitEvidenceDiagnosticCode code;

  GitValidationException(GitEvidenceDiagnosticCode code, String detail) {
    super(detail);
    this.code = code;
  }

  GitEvidenceDiagnosticCode code() {
    return code;
  }
}
