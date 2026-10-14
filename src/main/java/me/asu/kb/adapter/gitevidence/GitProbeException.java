package me.asu.kb.adapter.gitevidence;

/** A structured failure raised by the fixed read-only repository probe. */
final class GitProbeException extends Exception {
  private final GitEvidenceDiagnosticCode code;

  GitProbeException(GitEvidenceDiagnosticCode code, String detail) {
    super(detail);
    this.code = code;
  }

  GitEvidenceDiagnosticCode code() {
    return code;
  }
}
