package me.asu.kb.adapter.symbol;

import java.util.List;
import java.util.Objects;

/** The complete or explicitly incomplete outcome of one Ctags extraction. */
public record SymbolExtractionResult(
    List<ExtractedSymbol> symbols,
    CtagsToolProfile profile,
    List<SymbolExtractionDiagnostic> diagnostics,
    boolean complete) {

  public SymbolExtractionResult {
    symbols = symbols == null ? List.of() : List.copyOf(symbols);
    profile = Objects.requireNonNull(profile, "profile");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  /** True only for a fully completed extraction without warnings or failures. */
  public boolean successful() {
    return complete && diagnostics.isEmpty();
  }
}
