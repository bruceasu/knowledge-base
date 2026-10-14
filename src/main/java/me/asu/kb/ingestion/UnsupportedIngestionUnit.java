package me.asu.kb.ingestion;

import java.util.Objects;

/** Explicit descriptor for a known but non-persistable query-time source category. */
public record UnsupportedIngestionUnit(
    String stableSourceId, IngestionSourceCategory category) implements IngestionSourceUnit {

  public UnsupportedIngestionUnit {
    stableSourceId = requireText(stableSourceId, "stableSourceId");
    category = Objects.requireNonNull(category, "category");
    if (category != IngestionSourceCategory.RIPGREP_TEXT) {
      throw new IllegalArgumentException("only RIPGREP_TEXT is an explicit unsupported category");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
