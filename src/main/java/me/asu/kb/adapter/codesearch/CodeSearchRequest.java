package me.asu.kb.adapter.codesearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A literal text-search request. Request validation intentionally belongs to
 * {@link RipgrepCodeSearchAdapter#search(CodeSearchRequest)} so invalid caller input can be
 * reported as a deterministic diagnostic without starting a process.
 */
public record CodeSearchRequest(
    String workspaceId,
    String literalQuery,
    List<String> relativeScopes,
    int contextLines,
    int maxResults) {

  public CodeSearchRequest {
    relativeScopes =
        relativeScopes == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(relativeScopes));
  }

  /**
   * Creates an exact search that uses the Adapter's configured result budget ({@code maxResults =
   * 0}) and no context.
   */
  public static CodeSearchRequest exact(String workspaceId, String literalQuery) {
    return new CodeSearchRequest(workspaceId, literalQuery, List.of(), 0, 0);
  }
}
