package me.asu.kb.adapter.gitevidence;

import java.nio.file.Path;
import java.util.Objects;

/** Canonical local repository facts returned by the fixed profile probe. */
record GitRepositoryFacts(
    Path gitRoot, String objectFormat, boolean shallowRepository, String effectiveVersion) {

  GitRepositoryFacts {
    gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
    objectFormat = requireText(objectFormat, "objectFormat");
    effectiveVersion = requireText(effectiveVersion, "effectiveVersion");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
