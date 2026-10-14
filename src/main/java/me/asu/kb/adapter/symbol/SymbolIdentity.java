package me.asu.kb.adapter.symbol;

import java.util.Objects;

/**
 * A versioned Adapter-local key for one static-analysis candidate.
 *
 * <p>It is not a core knowledge-object identity or a persistence key.
 */
public record SymbolIdentity(String value, boolean locationSensitive) {

  public SymbolIdentity {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
