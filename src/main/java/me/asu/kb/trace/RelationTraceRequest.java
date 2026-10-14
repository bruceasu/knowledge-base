package me.asu.kb.trace;

import java.util.Objects;

/** Stable relation-trace input scoped to one registered engineering workspace. */
public record RelationTraceRequest(String workspaceId, String startObjectId) {
  public RelationTraceRequest {
    workspaceId = requireText(workspaceId, "workspaceId");
    startObjectId = requireText(startObjectId, "startObjectId");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
