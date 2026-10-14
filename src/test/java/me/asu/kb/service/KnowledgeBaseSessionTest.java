package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.asu.kb.ask.AskResponse;
import me.asu.kb.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseSessionTest {

  @TempDir
  Path tempDir;

  @Test
  void askUsesSelectedSessionSummaryAndRecentMessages() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(
        docsDir.resolve("fallback.md"),
        "Lucene-only fallback remains available for search and ask.",
        StandardCharsets.UTF_8);

    KnowledgeBase kb = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      kb.sync(docsDir);

      long sessionId = kb.sessions().create("Work");
      kb.sessions().appendUserMessage(sessionId, "Remember the Lucene fallback rule.");
      kb.sessions().appendAssistantMessage(sessionId, "Lucene-only fallback remains available.");
      kb.sessions().upsertSummary(sessionId, "We are discussing fallback behavior.");

      AskResponse response = kb.ask(sessionId, "What is the fallback rule?", 3);

      assertTrue(response.prompt().contains("We are discussing fallback behavior."));
      assertTrue(response.prompt().contains("Remember the Lucene fallback rule."));
      assertFalse(response.sources().isEmpty());
    } finally {
      kb.close();
    }
  }

  @Test
  void askWithoutSessionStillWorks() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(
        docsDir.resolve("lucene.md"),
        "Lucene indexes local knowledge for search.",
        StandardCharsets.UTF_8);

    KnowledgeBase kb = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      kb.sync(docsDir);

      AskResponse response = kb.ask("What does Lucene do?", 5);

      assertTrue(response.answer().startsWith("ANSWER:"));
      assertFalse(response.sources().isEmpty());
    } finally {
      kb.close();
    }
  }

  private static final class EchoLlmClient implements LlmClient {
    @Override
    public String generate(String prompt) {
      return "ANSWER: " + prompt;
    }
  }
}
