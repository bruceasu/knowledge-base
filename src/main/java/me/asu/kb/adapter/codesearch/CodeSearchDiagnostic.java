package me.asu.kb.adapter.codesearch;

import java.util.Objects;

/** A safe, structured explanation of an invalid, incomplete, or failed code search. */
public record CodeSearchDiagnostic(CodeSearchDiagnosticCode code, String detail) {

  public CodeSearchDiagnostic {
    Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }
}
