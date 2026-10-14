package me.asu.kb.core.knowledge;

/** Describes how a relationship assertion was established. */
public enum EvidenceType {
  SOURCE_TEXT,
  STATIC_ANALYSIS,
  TEST_EXECUTION,
  VERSION_CONTROL,
  MANUAL_ASSERTION,
  LLM_DERIVATION
}
