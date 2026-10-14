package me.asu.kb.read;

import java.util.Objects;

/** Stable object-read input scoped to one registered engineering workspace. */
public record KnowledgeObjectReadRequest(String workspaceId, String objectId) {
  public KnowledgeObjectReadRequest {
    workspaceId = requireText(workspaceId, "workspaceId");
    objectId = requireText(objectId, "objectId");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
