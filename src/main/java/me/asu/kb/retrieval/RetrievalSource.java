package me.asu.kb.retrieval;

/** Candidate or enrichment source hidden behind the retrieval Module. */
public enum RetrievalSource {
  STRUCTURED_STORE,
  LUCENE_BM25,
  VIRTUAL_PROJECT_LUCENE,
  RIPGREP_TEXT,
  CTAGS_SYMBOL,
  GIT_EVIDENCE
}
