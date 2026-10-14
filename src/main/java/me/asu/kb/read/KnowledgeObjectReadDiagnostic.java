package me.asu.kb.read;

import java.util.Objects;

/** Safe diagnostic for a controlled object-read outcome. */
public record KnowledgeObjectReadDiagnostic(Code code, String detail) {
  public KnowledgeObjectReadDiagnostic {
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }

  public enum Code {
    UNKNOWN_WORKSPACE,
    STORE_UNAVAILABLE,
    EVIDENCE_INCOMPLETE
  }
}
