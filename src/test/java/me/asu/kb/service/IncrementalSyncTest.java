package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.io.IOException;
import me.asu.kb.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalSyncTest {

  @TempDir
  Path tempDir;

  @Test
  void syncRemovesDeletedDocumentsFromIndex() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path file = docsDir.resolve("note.txt");
    Files.writeString(file, "alpha", StandardCharsets.UTF_8);

    KnowledgeBase kb = new KnowledgeBase(tempDir, prompt -> "ok");
    try {
      kb.sync(docsDir);
      assertEquals(1, kb.search("alpha", 10).size());

      Files.delete(file);
      kb.sync(docsDir);

      assertEquals(0, kb.search("alpha", 10).size());
    } finally {
      kb.close();
    }
  }

  @Test
  void changedSourceCreatesOneRevisionAndRepeatedSyncDoesNotDuplicateIt() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("revision-docs"));
    Path file = docsDir.resolve("note.txt");
    Files.writeString(file, "first revision", StandardCharsets.UTF_8);

    KnowledgeBase kb = new KnowledgeBase(tempDir, prompt -> "ok");
    try {
      kb.sync(docsDir);
      long documentId = kb.documents().get(0).id();
      assertEquals(1, kb.revisionHistory(documentId).revisions().size());

      Files.writeString(file, "second revision", StandardCharsets.UTF_8);
      kb.sync(docsDir);
      assertEquals(2, kb.revisionHistory(documentId).revisions().size());
      assertEquals(2, kb.revisionHistory(documentId).current().revisionNumber());

      kb.sync(docsDir);
      assertEquals(2, kb.revisionHistory(documentId).revisions().size());
    } finally {
      kb.close();
    }
  }

  @Test
  void interruptedChunkReplacementRollsBackAndLaterSyncRecovers() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("failure-docs"));
    Path file = docsDir.resolve("note.txt");
    Files.writeString(file, "committed content", StandardCharsets.UTF_8);
    KnowledgeBase kb = new KnowledgeBase(tempDir, prompt -> "ok");
    try {
      kb.sync(docsDir);
      long documentId = kb.documents().get(0).id();
      String originalHash = kb.document(documentId).contentHash();
      Files.writeString(file, "replacement content", StandardCharsets.UTF_8);

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("data/kb.sqlite").toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER fail_chunk_insert BEFORE INSERT ON chunks "
            + "BEGIN SELECT RAISE(ABORT, 'injected chunk failure'); END");
      }
      try {
        kb.sync(docsDir);
        throw new AssertionError("sync should fail while the chunk trigger is installed");
      } catch (Exception expected) {
        assertEquals(originalHash, kb.document(documentId).contentHash());
      }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("data/kb.sqlite").toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER fail_chunk_insert");
      }
      kb.sync(docsDir);
      assertEquals("replacement content", kb.document(documentId).content());
    } finally {
      kb.close();
    }
  }

  @Test
  void projectionCommitFailureLeavesDurableBatchForExplicitRecovery() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("projection-failure-docs"));
    Files.writeString(docsDir.resolve("note.txt"), "recoverable projection", StandardCharsets.UTF_8);
    KnowledgeBase failing = new KnowledgeBase(tempDir, prompt -> "ok", EmbeddingClient.disabled(),
        indexDir -> new LuceneSearchIndex(indexDir, () -> {
          throw new IOException("injected projection failure");
        }));
    try {
      try {
        failing.sync(docsDir);
        throw new AssertionError("sync should report the injected projection failure");
      } catch (IOException expected) {
        assertEquals(1, failing.pendingProjectionBatchCount());
      }
    } finally {
      failing.close();
    }

    try (KnowledgeBase recovered = new KnowledgeBase(tempDir, prompt -> "ok")) {
      recovered.sync(docsDir);
      assertEquals(0, recovered.pendingProjectionBatchCount());
      assertEquals(1, recovered.documents().size());
    }
  }
}
