package me.asu.kb.context;

/** Bounded intent-level request without retrieval, graph, Store, or tool parameters. */
public record ContextRequest(
    String workspaceId,
    String target,
    ContextIntent intent,
    int tokenBudget,
    boolean includeStale,
    boolean requireEvidence) {

  public ContextRequest {
    workspaceId = workspaceId == null ? "" : workspaceId;
    target = target == null ? "" : target;
  }
}
