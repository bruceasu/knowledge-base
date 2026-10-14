package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.retrieval.RetrievalFreshness;

/** Infrastructure-neutral direct relation plus the resolved opposite endpoint. */
record DirectContextRelation(
    String workspaceId,
    String relationId,
    String anchorObjectId,
    String sourceObjectId,
    RelationType type,
    String targetObjectId,
    KnowledgeObject relatedObject,
    RetrievalFreshness freshness,
    List<Evidence> evidence) {

  DirectContextRelation {
    workspaceId = requireText(workspaceId, "workspaceId");
    relationId = requireText(relationId, "relationId");
    anchorObjectId = requireText(anchorObjectId, "anchorObjectId");
    sourceObjectId = requireText(sourceObjectId, "sourceObjectId");
    type = Objects.requireNonNull(type, "type");
    targetObjectId = requireText(targetObjectId, "targetObjectId");
    relatedObject = Objects.requireNonNull(relatedObject, "relatedObject");
    freshness = Objects.requireNonNull(freshness, "freshness");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));

    if (!anchorObjectId.equals(sourceObjectId) && !anchorObjectId.equals(targetObjectId)) {
      throw new IllegalArgumentException("relation must touch the anchor object");
    }
    String relatedObjectId =
        anchorObjectId.equals(sourceObjectId) ? targetObjectId : sourceObjectId;
    if (!relatedObject.id().equals(relatedObjectId)) {
      throw new IllegalArgumentException("related object must resolve the opposite endpoint");
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
