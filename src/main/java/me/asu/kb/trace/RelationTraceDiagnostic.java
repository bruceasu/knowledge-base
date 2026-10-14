package me.asu.kb.trace;

import java.util.Objects;

/** Safe diagnostic for a controlled relation-trace outcome. */
public record RelationTraceDiagnostic(Code code, String detail) {
  public RelationTraceDiagnostic {
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }

  public enum Code {
    UNKNOWN_WORKSPACE,
    STORE_UNAVAILABLE,
    RESULT_LIMIT_REACHED,
    RELATION_INCOMPLETE
  }
}
