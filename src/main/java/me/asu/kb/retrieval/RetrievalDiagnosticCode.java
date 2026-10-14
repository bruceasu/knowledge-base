package me.asu.kb.retrieval;

/** Stable query/source/freshness diagnostic vocabulary. */
public enum RetrievalDiagnosticCode {
  INVALID_QUERY,
  UNKNOWN_WORKSPACE,
  LIMIT_EXCEEDED,
  SOURCE_UNAVAILABLE,
  SOURCE_FAILED,
  SOURCE_TRUNCATED,
  UNMAPPABLE_CANDIDATE,
  STALE_CANDIDATE,
  PROVENANCE_INCOMPLETE
}
