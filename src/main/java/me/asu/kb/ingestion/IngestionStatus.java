package me.asu.kb.ingestion;

/** Stable per-source-unit ingestion outcome vocabulary. */
public enum IngestionStatus {
  SAVED,
  UNCHANGED,
  STALE,
  FAILED,
  UNSUPPORTED
}
