package me.asu.kb.core.knowledge;

import java.util.Objects;

/** A typed relationship between two stable knowledge-object identifiers, backed by evidence. */
public record Relation(
    String sourceObjectId, String targetObjectId, RelationType type, Evidence evidence) {

  public Relation {
    sourceObjectId = requireText(sourceObjectId, "sourceObjectId");
    targetObjectId = requireText(targetObjectId, "targetObjectId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(evidence, "evidence");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
