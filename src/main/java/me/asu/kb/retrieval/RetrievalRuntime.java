package me.asu.kb.retrieval;

import java.io.IOException;
import java.sql.SQLException;

/** Owned retrieval resources exposed to the application composition. */
public interface RetrievalRuntime extends AutoCloseable {
  KnowledgeRetriever retriever();

  void rebuild() throws IOException, SQLException;

  @Override
  void close() throws IOException;
}
