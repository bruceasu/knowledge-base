package me.asu.kb.adapter.codesearch;

/** Application seam for bounded, literal, read-only code search. */
@FunctionalInterface
public interface ConstrainedCodeSearcher {
  CodeSearchResult search(CodeSearchRequest request);
}
