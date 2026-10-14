package me.asu.kb.adapter.gitevidence;

import java.util.Objects;
import java.util.Optional;

/** Fixed local-readonly Git profile plus observed repository/tool properties. */
public record GitToolProfile(
    String profileId,
    Optional<String> effectiveVersion,
    Optional<String> objectFormat,
    Optional<Boolean> shallowRepository) {

  public GitToolProfile {
    profileId = requireText(profileId, "profileId");
    effectiveVersion = optionalText(effectiveVersion, "effectiveVersion");
    objectFormat = optionalText(objectFormat, "objectFormat");
    shallowRepository = Objects.requireNonNull(shallowRepository, "shallowRepository");
  }

  private static Optional<String> optionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(item -> requireText(item, name));
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
