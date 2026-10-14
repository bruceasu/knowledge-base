package me.asu.kb.workspace;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable application-layer description of one registered engineering workspace. */
public record EngineeringWorkspace(String id, Path root) {
  private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

  public EngineeringWorkspace {
    Objects.requireNonNull(id, "id");
    if (!id.matches(ID_PATTERN)) {
      throw new IllegalArgumentException("invalid workspace id");
    }
    root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
  }
}
