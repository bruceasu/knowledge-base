package me.asu.kb.adapter.symbol;

import java.util.Objects;

/** A safe, structured explanation of a symbol-extraction outcome. */
public record SymbolExtractionDiagnostic(SymbolExtractionDiagnosticCode code, String detail) {

  public SymbolExtractionDiagnostic {
    Objects.requireNonNull(code, "code");
    detail = detail == null ? "" : detail;
  }
}
