package me.asu.kb.adapter.symbol;

import java.util.Objects;
import java.util.Optional;

/** A one-based source-line range; Ctags may omit the inclusive end line. */
public record SymbolSourceRange(int startLine, Optional<Integer> endLine) {

  public SymbolSourceRange {
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine must be positive");
    }
    Objects.requireNonNull(endLine, "endLine");
    endLine.ifPresent(
        value -> {
          if (value < startLine) {
            throw new IllegalArgumentException("endLine must not precede startLine");
          }
        });
  }
}
