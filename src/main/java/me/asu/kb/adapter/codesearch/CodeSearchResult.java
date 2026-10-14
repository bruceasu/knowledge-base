package me.asu.kb.adapter.codesearch;

import java.util.List;

/** The complete or explicitly incomplete outcome of one code-search request. */
public record CodeSearchResult(
    List<CodeSearchMatch> matches, List<CodeSearchDiagnostic> diagnostics, boolean complete) {

  public CodeSearchResult {
    matches = matches == null ? List.of() : List.copyOf(matches);
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  /** True only for a fully completed search with no invalid-request or tool diagnostic. */
  public boolean successful() {
    return complete && diagnostics.isEmpty();
  }
}
