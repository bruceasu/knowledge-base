package me.asu.kb.retrieval;

/** Cross-source match tier; raw source scores are deliberately absent. */
public enum RetrievalMatchCategory {
  EXACT_IDENTIFIER,
  EXACT_SYMBOL,
  STRUCTURED_OBJECT,
  EXACT_TEXT,
  BM25_LEXICAL
}
