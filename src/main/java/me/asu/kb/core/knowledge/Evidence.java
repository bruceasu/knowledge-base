package me.asu.kb.core.knowledge;

import java.util.Objects;

/** Auditable support for a relationship assertion. */
public record Evidence(EvidenceType type, SourceRef source, String location, double confidence) {

  public Evidence {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(source, "source");
    location = requireText(location, "location");
    if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
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
