package me.asu.kb.retrieval;

/** Single application-layer seam for bounded, explainable engineering-knowledge retrieval. */
@FunctionalInterface
public interface KnowledgeRetriever {
  RetrievalResult retrieve(KnowledgeQuery query);
}
