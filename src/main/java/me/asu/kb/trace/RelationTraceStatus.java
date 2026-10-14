package me.asu.kb.trace;

/** Controlled outcomes that do not expose persistence exceptions. */
public enum RelationTraceStatus {
  FOUND,
  NOT_FOUND,
  UNAVAILABLE
}
