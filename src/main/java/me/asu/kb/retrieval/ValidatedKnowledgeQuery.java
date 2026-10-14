package me.asu.kb.retrieval;

import java.nio.file.Path;
import java.util.Set;
import me.asu.kb.core.knowledge.ObjectType;

/** Canonical, bounded query visible only to retrieval implementations. */
record ValidatedKnowledgeQuery(
    String workspaceId,
    Path workspaceRoot,
    String text,
    Set<ObjectType> objectTypes,
    boolean includeStale,
    boolean requireEvidence,
    RetrievalBudgets budgets,
    String virtualProjectId) {

  /** Backward-compatible shared-scope validated query constructor. */
  ValidatedKnowledgeQuery(
      String workspaceId,
      Path workspaceRoot,
      String text,
      Set<ObjectType> objectTypes,
      boolean includeStale,
      boolean requireEvidence,
      RetrievalBudgets budgets) {
    this(workspaceId, workspaceRoot, text, objectTypes, includeStale, requireEvidence, budgets, "");
  }

  ValidatedKnowledgeQuery {
    objectTypes = Set.copyOf(objectTypes);
    virtualProjectId = virtualProjectId == null ? "" : virtualProjectId.trim();
  }
}
