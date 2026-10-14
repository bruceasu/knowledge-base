package me.asu.kb.ingestion;

/** Stable validation, normalization, Adapter, and Store outcome codes. */
public enum IngestionDiagnosticCode {
  INVALID_REQUEST,
  UNKNOWN_WORKSPACE,
  INVALID_SCOPE,
  LIMIT_EXCEEDED,
  UNSUPPORTED_CATEGORY,
  SNAPSHOT_UNAVAILABLE,
  ADAPTER_INCOMPLETE,
  NORMALIZATION_FAILED,
  STORE_FAILED
}
