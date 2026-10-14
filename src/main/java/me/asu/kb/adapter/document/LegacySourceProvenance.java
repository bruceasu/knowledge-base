package me.asu.kb.adapter.document;

import java.util.Objects;
import java.util.Optional;

/** Caller-supplied logical source data required to construct a core {@code SourceRef}. */
public record LegacySourceProvenance(
    Optional<String> system, Optional<String> locator, Optional<String> revision) {

  public LegacySourceProvenance {
    system = normalize(system, "system");
    locator = normalize(locator, "locator");
    revision = normalize(revision, "revision");
  }

  private static Optional<String> normalize(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    return value.filter(item -> !item.isBlank());
  }
}
