package me.asu.kb.retrieval;

import java.util.Set;
import me.asu.kb.core.knowledge.ObjectType;

/** Intent-level query without Lucene, SQL, or external-tool arguments. */
public record KnowledgeQuery(
    String workspaceId,
    String text,
    Set<ObjectType> objectTypes,
    int resultLimit,
    boolean includeStale,
    boolean requireEvidence,
    String virtualProjectId) {

  /** Backward-compatible shared-scope query constructor. */
  public KnowledgeQuery(
      String workspaceId,
      String text,
      Set<ObjectType> objectTypes,
      int resultLimit,
      boolean includeStale,
      boolean requireEvidence) {
    this(workspaceId, text, objectTypes, resultLimit, includeStale, requireEvidence, "");
  }

  public KnowledgeQuery {
    workspaceId = workspaceId == null ? "" : workspaceId;
    text = text == null ? "" : text;
    objectTypes = objectTypes == null ? Set.of() : Set.copyOf(objectTypes);
    virtualProjectId = virtualProjectId == null ? "" : virtualProjectId.trim();
  }

  public static KnowledgeQuery of(String workspaceId, String text) {
    return new KnowledgeQuery(workspaceId, text, Set.of(), 0, false, false, "");
  }
}
