package me.asu.kb.core.knowledge;

import java.util.Objects;
import java.util.Optional;

/** Identifies the source system and locator from which a knowledge object or evidence originates. */
public record SourceRef(String system, String locator, Optional<String> revision) {

  public SourceRef {
    system = requireText(system, "system");
    locator = requireText(locator, "locator");
    Objects.requireNonNull(revision, "revision");
    revision.ifPresent(value -> requireText(value, "revision"));
  }

  public static SourceRef of(String system, String locator) {
    return new SourceRef(system, locator, Optional.empty());
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
