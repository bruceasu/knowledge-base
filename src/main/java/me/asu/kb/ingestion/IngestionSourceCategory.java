package me.asu.kb.ingestion;

/** Closed source vocabulary; RIPGREP_TEXT is explicitly query-time-only and unsupported here. */
public enum IngestionSourceCategory {
  DOCUMENT,
  JAVA_SYMBOLS,
  GIT_EVIDENCE,
  RIPGREP_TEXT
}
