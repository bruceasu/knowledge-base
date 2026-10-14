package me.asu.kb.adapter.symbol;

import java.util.Objects;
import java.util.Optional;

/** The fixed Ctags output profile and, when observed, its effective tool version. */
public record CtagsToolProfile(String profileId, Optional<String> effectiveVersion) {

  public CtagsToolProfile {
    profileId = requireText(profileId, "profileId");
    Objects.requireNonNull(effectiveVersion, "effectiveVersion");
    effectiveVersion.ifPresent(value -> requireText(value, "effectiveVersion"));
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
