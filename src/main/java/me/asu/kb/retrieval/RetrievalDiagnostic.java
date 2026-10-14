package me.asu.kb.retrieval;

import java.util.Objects;
import java.util.Optional;

/** Safe structured diagnostic for the query or one candidate source. */
public record RetrievalDiagnostic(
    Optional<RetrievalSource> source, RetrievalDiagnosticCode code, String detail) {

  public RetrievalDiagnostic {
    source = Objects.requireNonNull(source, "source");
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }
}
