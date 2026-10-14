package me.asu.kb.context;

/** Closed vocabulary explaining candidate exclusion, merging, or truncation. */
public enum OmissionReason {
  BUDGET,
  STALE,
  MISSING_EVIDENCE,
  LOW_CONFIDENCE_EVIDENCE,
  DUPLICATE_MERGED,
  SOURCE_FAILURE,
  INVALID_SOURCE,
  MISSING_OBJECT,
  UNSUPPORTED_RELATION,
  WORKSPACE_MISMATCH
}
