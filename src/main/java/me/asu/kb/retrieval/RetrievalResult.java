package me.asu.kb.retrieval;

import java.util.List;

/** Complete or explicitly partial outcome of one bounded retrieval request. */
public record RetrievalResult(
    String workspaceId,
    List<RetrievalItem> items,
    List<RetrievalDiagnostic> diagnostics,
    boolean complete) {

  public RetrievalResult {
    workspaceId = workspaceId == null ? "" : workspaceId;
    items = items == null ? List.of() : List.copyOf(items);
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    if (complete && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("a complete result cannot contain diagnostics");
    }
  }
}
