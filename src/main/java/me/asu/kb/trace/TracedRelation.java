package me.asu.kb.trace;

import java.util.List;
import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.RelationType;

/** One direct relation with both endpoint sources/freshness and its supporting evidence. */
public record TracedRelation(
    String relationId,
    RelationTraceObject source,
    RelationType relationType,
    RelationTraceObject target,
    List<Evidence> evidence) {
  public TracedRelation {
    relationId = requireText(relationId, "relationId");
    source = Objects.requireNonNull(source, "source");
    relationType = Objects.requireNonNull(relationType, "relationType");
    target = Objects.requireNonNull(target, "target");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    if (evidence.isEmpty()) {
      throw new IllegalArgumentException("traced relation requires evidence");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
