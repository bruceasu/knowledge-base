package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import me.asu.kb.embedding.FixedEmbeddingClient;
import me.asu.kb.llm.EchoLlmClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseEmbeddingTest {

  @TempDir
  Path tempDir;

  @Test
  void rebuildRestoresChunkEmbeddingsFromStoredChunks() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path file = docsDir.resolve("vector.md");
    Files.writeString(file, "# Vector\n\nEmbedding should be stored and rebuilt.", StandardCharsets.UTF_8);

    try (KnowledgeBase kb =
        new KnowledgeBase(
            tempDir, new EchoLlmClient(), new FixedEmbeddingClient("fixed-model", new float[] {1f, 2f, 3f}))) {
      kb.sync(docsDir);

      List<KnowledgeRepository.ChunkRow> chunks;
      try (KnowledgeRepository repo = new KnowledgeRepository(tempDir.resolve("data/kb.sqlite"))) {
        chunks = repo.findAllChunks();
        assertFalse(chunks.isEmpty());
        List<Long> chunkIds = chunks.stream().map(KnowledgeRepository.ChunkRow::id).toList();
        assertFalse(repo.findChunkEmbeddingsByChunkIds(chunkIds).isEmpty());
      }

      clearChunkEmbeddings(tempDir.resolve("data/kb.sqlite"));

      try (KnowledgeRepository repo = new KnowledgeRepository(tempDir.resolve("data/kb.sqlite"))) {
        assertTrue(repo.findAllChunks().size() > 0);
        assertEquals(0, repo.findChunkEmbeddingsByChunkIds(repo.findAllChunks().stream().map(KnowledgeRepository.ChunkRow::id).toList()).size());
      }

      kb.rebuild();

      try (KnowledgeRepository repo = new KnowledgeRepository(tempDir.resolve("data/kb.sqlite"))) {
        List<Long> chunkIds = repo.findAllChunks().stream().map(KnowledgeRepository.ChunkRow::id).toList();
        assertFalse(repo.findChunkEmbeddingsByChunkIds(chunkIds).isEmpty());
      }
    }
  }

  private void clearChunkEmbeddings(Path dbFile) throws Exception {
    String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM chunk_embeddings");
    }
  }
}
