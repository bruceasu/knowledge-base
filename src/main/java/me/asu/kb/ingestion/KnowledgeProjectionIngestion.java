package me.asu.kb.ingestion;

/** Single application-layer seam for bounded engineering-knowledge projection ingestion. */
@FunctionalInterface
public interface KnowledgeProjectionIngestion {

  IngestionReport ingest(IngestionRequest request);
}
