package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.asu.kb.ask.AskResponse;
import me.asu.kb.llm.LlmClient;
import me.asu.kb.search.SearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseSummaryTest {

  @TempDir
  Path tempDir;

  @Test
  void ingestSearchAndAskExposeDocumentSummary() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path file = docsDir.resolve("summary.md");
    Files.writeString(
        file,
        "# Summary\n\nLucene indexes local knowledge and supports search.\n\nThe document explains the index pipeline.",
        StandardCharsets.UTF_8);

    KnowledgeBase kb = new KnowledgeBase(tempDir, new SummaryAwareLlmClient());
    try {
      kb.sync(docsDir);

      List<SearchHit> hits = kb.search("Lucene", 5);
      assertFalse(hits.isEmpty());
      assertTrue(hits.get(0).summary() != null && !hits.get(0).summary().isBlank());
      assertTrue(hits.get(0).summary().contains("DOCUMENT SUMMARY"));

      AskResponse response = kb.ask("What does Lucene do?", 5);
      assertTrue(response.prompt().contains("DOCUMENT SUMMARY"));
      assertTrue(response.answer().contains("ASK ANSWER"));
    } finally {
      kb.close();
    }
  }

  private static final class SummaryAwareLlmClient implements LlmClient {
    @Override
    public String generate(String prompt) {
      if (prompt.contains("Please write a concise summary for the document below")) {
        return "DOCUMENT SUMMARY";
      }
      return "ASK ANSWER";
    }
  }
}
