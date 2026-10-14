package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeRepositorySummaryTest {

  @TempDir
  Path tempDir;

  @Test
  void documentSummaryRoundTripsThroughRepository() throws Exception {
    Path db = tempDir.resolve("kb.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(db)) {
      long documentId = repo.upsertDocument("/root", "/root/doc.md", "md", "UTF-8", "hash-1");
      repo.upsertDocumentSummary(documentId, "Document summary", "LlmSummaryService");

      KnowledgeRepository.DocumentRow row = repo.findDocumentById(documentId).orElseThrow();
      assertTrue(row.summary().isPresent());
      assertEquals("Document summary", row.summary().orElseThrow());
      assertEquals("LlmSummaryService", row.summaryModel().orElseThrow());
      assertTrue(row.summaryUpdatedAt().isPresent());
    }
  }
}
