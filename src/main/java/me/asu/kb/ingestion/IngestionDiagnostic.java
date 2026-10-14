package me.asu.kb.ingestion;

import java.util.Objects;

/** Safe structured explanation of one source-unit ingestion outcome. */
public record IngestionDiagnostic(IngestionDiagnosticCode code, String detail) {

  public IngestionDiagnostic {
    code = Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }
}
