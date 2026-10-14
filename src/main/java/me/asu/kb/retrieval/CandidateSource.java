package me.asu.kb.retrieval;

/** Internal source seam receives only a validated query with centrally assigned budgets. */
interface CandidateSource {
  RetrievalSource source();

  CandidateBatch collect(ValidatedKnowledgeQuery query);
}
