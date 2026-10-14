package me.asu.kb.adapter.document;

import java.util.Objects;

/** Known generator information retained from a legacy chunk when it is available. */
public record LegacyGeneratorProvenance(String id, String version) {

  public LegacyGeneratorProvenance {
    id = requireText(id, "id");
    version = requireText(version, "version");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
