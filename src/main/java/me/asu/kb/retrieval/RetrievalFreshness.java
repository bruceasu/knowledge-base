package me.asu.kb.retrieval;

/** Store-verifiable lifecycle of a returned candidate. */
public enum RetrievalFreshness {
  CURRENT_CANONICAL,
  CURRENT_DERIVED,
  STALE,
  RETRYABLE,
  UNVERIFIED
}
