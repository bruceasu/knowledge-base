package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeRepositorySessionTest {

  @TempDir
  Path tempDir;

  @Test
  void sessionRowsRoundTripThroughRepository() throws Exception {
    Path dbFile = tempDir.resolve("kb.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long sessionId = repo.insertSession("Daily notes");
      repo.insertSessionMessage(sessionId, 1, "user", "What changed today?");
      repo.insertSessionMessage(sessionId, 2, "assistant", "You asked about daily notes.");
      repo.upsertSessionSummary(sessionId, 1, "The session is about daily notes and change tracking.");

      assertTrue(repo.findSessionById(sessionId).isPresent());
      assertEquals(1, repo.findAllSessions().size());
      assertEquals(2, repo.findMessagesBySessionId(sessionId).size());
      assertEquals(1, repo.findSummariesBySessionId(sessionId).size());
      assertFalse(repo.findSessionById(sessionId).orElseThrow().archivedAt().isPresent());
    }
  }
}
