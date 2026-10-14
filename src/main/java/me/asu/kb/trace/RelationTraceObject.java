package me.asu.kb.trace;

import java.util.Objects;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;

/** Minimal immutable endpoint view used by a relation trace. */
public record RelationTraceObject(
    String objectId, ObjectType objectType, SourceRef source, RetrievalFreshness freshness) {
  public RelationTraceObject {
    objectId = requireText(objectId, "objectId");
    objectType = Objects.requireNonNull(objectType, "objectType");
    source = Objects.requireNonNull(source, "source");
    freshness = Objects.requireNonNull(freshness, "freshness");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
