package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeRepositoryTest {

  @TempDir
  Path tempDir;

  @Test
  void isolatedReadTransactionSeesOnlyThePriorCommittedDocument() throws Exception {
    Path dbFile = tempDir.resolve("read-snapshot.sqlite");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      String path = tempDir.resolve("document.md").toString();
      long documentId = repo.upsertDocument(tempDir.toString(), path, "markdown", "UTF-8", "hash-v1");
      repo.insertChunk(documentId, 0, "old", "old content", 1, 1);
      CountDownLatch uncommittedReplacement = new CountDownLatch(1);
      CountDownLatch allowCommit = new CountDownLatch(1);

      Future<?> writer = executor.submit(() -> repo.inTransaction(() -> {
        repo.upsertDocument(tempDir.toString(), path, "markdown", "UTF-8", "hash-v2");
        repo.insertChunk(documentId, 0, "new", "new content", 1, 1);
        uncommittedReplacement.countDown();
        assertTrue(allowCommit.await(2, TimeUnit.SECONDS));
        return null;
      }));
      assertTrue(uncommittedReplacement.await(2, TimeUnit.SECONDS));

      String observedHash = repo.inReadTransaction(ignored ->
          repo.findDocumentById(documentId).orElseThrow().hash());
      List<KnowledgeRepository.ChunkRow> observedChunks = repo.inReadTransaction(ignored ->
          repo.findChunksByDocumentId(documentId));
      assertEquals("hash-v1", observedHash);
      assertEquals("old content", observedChunks.get(0).content());

      allowCommit.countDown();
      writer.get(2, TimeUnit.SECONDS);
      assertEquals("hash-v2", repo.findDocumentById(documentId).orElseThrow().hash());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void chunkEmbeddingsTableIsCreatedAndRoundTrips() throws Exception {
    Path dbFile = tempDir.resolve("kb.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long documentId = repo.upsertDocument(tempDir.toString(), tempDir.resolve("doc.md").toString(), "md", "UTF-8", "hash");
      long chunkId = repo.insertChunk(documentId, 0, "Title", "content", 1, 1);
      repo.upsertChunkEmbedding(chunkId, "nomic-embed-text", "[0.1,0.2,0.3]", 3);

      Optional<KnowledgeRepository.ChunkEmbeddingRow> row = repo.findChunkEmbeddingByChunkId(chunkId);
      assertTrue(row.isPresent());
      assertEquals("nomic-embed-text", row.get().embeddingModel());
      assertEquals("[0.1,0.2,0.3]", row.get().vectorJson());
      assertEquals(3, row.get().vectorDim());
    }
  }

  @Test
  void documentTagsRoundTripAndDeleteWithDocument() throws Exception {
    Path dbFile = tempDir.resolve("kb.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long documentId =
          repo.upsertDocument(
              tempDir.toString(), tempDir.resolve("docs/guide.md").toString(), "markdown", "UTF-8", "hash");
      repo.upsertDocumentTag(documentId, "markdown", "system");
      repo.upsertDocumentTag(documentId, "doc", "system");
      repo.upsertDocumentTag(documentId, "aws", "path");

      assertEquals(3, repo.findTagsByDocumentId(documentId).size());
      repo.deleteTagsByDocumentId(documentId);
      assertTrue(repo.findTagsByDocumentId(documentId).isEmpty());

      repo.upsertDocumentTag(documentId, "markdown", "system");
      repo.upsertDocumentTag(documentId, "doc", "system");
      repo.upsertDocumentTag(documentId, "aws", "path");
      repo.deleteDocument(documentId);

      assertFalse(repo.findDocumentById(documentId).isPresent());
      assertTrue(repo.findTagsByDocumentId(documentId).isEmpty());
    }
  }

  @Test
  void documentMetadataKeywordsAndUploadIdentityRoundTrip() throws Exception {
    Path dbFile = tempDir.resolve("metadata.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long documentId =
          repo.upsertDocument(tempDir.toString(), tempDir.resolve("doc.org").toString(), "org", "UTF-8", "hash");

      repo.upsertDocumentMetadata(documentId, "title", "Source title", "source", null);
      repo.upsertDocumentMetadata(documentId, "summary", "AI summary", "ai", "test-model");
      repo.upsertDocumentKeyword(documentId, "java", "source");
      repo.upsertDocumentKeyword(documentId, "search", "ai");
      repo.insertUploadIdentity(
          "sha256-1", documentId, "uploads/objects/sha256/sha256-1", "doc.org", "nested/doc.org", "batch-1", "2026-08-28T00:00:00Z");

      assertEquals(2, repo.findDocumentMetadata(documentId).size());
      assertEquals("test-model", repo.findDocumentMetadata(documentId).get(1).generatorModel().orElseThrow());
      assertEquals(2, repo.findDocumentKeywords(documentId).size());
      assertEquals(documentId, repo.findUploadByContentHash("sha256-1").orElseThrow().documentId());

      repo.deleteDocumentMetadataBySource(documentId, "ai");
      repo.deleteDocumentKeywordsBySource(documentId, "ai");
      assertEquals(1, repo.findDocumentMetadata(documentId).size());
      assertEquals(1, repo.findDocumentKeywords(documentId).size());
    }
  }

  @Test
  void effectiveMetadataUsesManualSourceAiFallbackPrecedence() throws Exception {
    Path dbFile = tempDir.resolve("effective-metadata.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long documentId =
          repo.upsertDocument(tempDir.toString(), tempDir.resolve("fallback.md").toString(), "md", "UTF-8", "hash");
      repo.insertChunk(documentId, 0, "Chunk fallback title", "content", 1, 1);
      repo.upsertDocumentMetadata(documentId, "title", "AI title", "ai", "ai-model");
      repo.upsertDocumentMetadata(documentId, "title", "Source title", "source", null);
      repo.upsertDocumentMetadata(documentId, "title", "Manual title", "manual", null);
      repo.upsertDocumentTag(documentId, "topic", "ai");
      repo.upsertDocumentTag(documentId, "topic", "source");
      repo.upsertDocumentTag(documentId, "topic", "manual");
      repo.upsertDocumentKeyword(documentId, "low", "ai");
      repo.upsertDocumentKeyword(documentId, "high", "manual");

      KnowledgeRepository.EffectiveDocumentMetadata metadata =
          repo.findEffectiveDocumentMetadata(documentId);
      assertEquals("Manual title", metadata.title().orElseThrow().value());
      assertEquals("manual", metadata.title().orElseThrow().source());
      assertEquals("manual", metadata.tags().get(0).source());
      assertEquals(List.of("low", "high"), metadata.keywords().stream().map(KnowledgeRepository.EffectiveMetadataValue::value).toList());
    }
  }

  @Test
  void reopensLegacyWorkspaceAndBackfillsMetadataWithProjectionBatch() throws Exception {
    Path dbFile = tempDir.resolve("legacy-metadata.sqlite");
    long documentId;
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      documentId = repo.upsertDocument(tempDir.toString(), tempDir.resolve("legacy.md").toString(), "md", "UTF-8", "hash");
      repo.insertChunk(documentId, 0, "Legacy chunk title", "legacy content", 1, 1);
      repo.upsertDocumentSummary(documentId, "Legacy summary", "legacy-model");
    }

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 'document-metadata-v1'");
      statement.executeUpdate("DELETE FROM document_metadata_values");
    }

    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      KnowledgeRepository.EffectiveDocumentMetadata metadata =
          repo.findEffectiveDocumentMetadata(documentId);
      assertEquals("Legacy chunk title", metadata.title().orElseThrow().value());
      assertEquals("Legacy summary", metadata.summary().orElseThrow().value());
      assertEquals(1, repo.pendingProjectionBatchCount());
      assertEquals(1, repo.findDocumentMetadata(documentId).size());
    }
  }

  @Test
  void articleRevisionMigrationBackfillsExistingDocumentsWithoutChangingMetadata() throws Exception {
    Path dbFile = tempDir.resolve("article-revisions.sqlite");
    long documentId;
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      documentId = repo.upsertDocument(
          tempDir.toString(), tempDir.resolve("legacy.md").toString(), "markdown", "UTF-8", "hash-v1");
      repo.upsertDocumentMetadata(documentId, "title", "Existing title", "manual", null);
      repo.upsertDocumentTag(documentId, "existing", "manual");
    }

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.executeUpdate("DROP TABLE article_revisions");
      statement.executeUpdate("DROP TABLE articles");
      statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 'article-revisions-v1'");
    }

    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      assertEquals("Existing title", repo.findDocumentMetadata(documentId).get(0).value());
      assertEquals(1, repo.findTagsByDocumentId(documentId).size());
    }

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        var articleStatement = connection.prepareStatement(
            "SELECT article_id, document_id, current_revision_number, state, projection_state "
                + "FROM articles WHERE document_id = ?");
        var revisionStatement = connection.prepareStatement(
            "SELECT revision_number, content_hash, format, source_path, source_kind "
                + "FROM article_revisions WHERE article_id = ?")) {
      articleStatement.setLong(1, documentId);
      try (var articles = articleStatement.executeQuery()) {
        assertTrue(articles.next());
        assertEquals(documentId, articles.getLong("article_id"));
        assertEquals(documentId, articles.getLong("document_id"));
        assertEquals(1, articles.getInt("current_revision_number"));
        assertEquals("ACTIVE", articles.getString("state"));
        assertEquals("INDEXED", articles.getString("projection_state"));
      }

      revisionStatement.setLong(1, documentId);
      try (var revisions = revisionStatement.executeQuery()) {
        assertTrue(revisions.next());
        assertEquals(1, revisions.getInt("revision_number"));
        assertEquals("hash-v1", revisions.getString("content_hash"));
        assertEquals("markdown", revisions.getString("format"));
        assertEquals(tempDir.resolve("legacy.md").toString(), revisions.getString("source_path"));
        assertEquals("ROOT", revisions.getString("source_kind"));
        assertFalse(revisions.next());
      }
    }
  }

  @Test
  void uploadIdentityPreservesSameNameDifferentContentProvenance() throws Exception {
    Path dbFile = tempDir.resolve("upload-provenance.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long first = repo.upsertDocument("/uploads", "/uploads/guide.md", "markdown", "UTF-8", "hash-a");
      long second = repo.upsertDocument("/uploads", "/uploads/guide-1.md", "markdown", "UTF-8", "hash-b");
      repo.insertUploadIdentity("hash-a", first, "uploads/guide.md", "guide.md", "docs/guide.md", "batch-a", "2026-08-28T00:00:00Z");
      repo.insertUploadIdentity("hash-b", second, "uploads/guide-1.md", "guide.md", "other/guide.md", "batch-b", "2026-08-28T00:01:00Z");

      assertEquals("docs/guide.md", repo.findUploadByContentHash("hash-a").orElseThrow().relativePath());
      assertEquals("other/guide.md", repo.findUploadByContentHash("hash-b").orElseThrow().relativePath());
      assertEquals("uploads/guide-1.md", repo.findUploadByContentHash("hash-b").orElseThrow().storagePath());
    }
  }

  @Test
  void revisionCreationKeepsStableArticleIdentityAndInheritedMetadata() throws Exception {
    Path dbFile = tempDir.resolve("article-revision-lifecycle.sqlite");
    try (KnowledgeRepository repo = new KnowledgeRepository(dbFile)) {
      long documentId = repo.upsertDocument(tempDir.toString(), tempDir.resolve("article.md").toString(),
          "markdown", "UTF-8", "hash-v1");
      repo.upsertDocumentMetadata(documentId, "title", "Manual title", "manual", null);
      Article before = repo.findArticleById(documentId).orElseThrow();
      ArticleRevision next = new ArticleRevision(2, "hash-v2", "markdown",
          tempDir.resolve("article.md").toString(), ArticleSourceKind.ROOT, Instant.parse("2026-08-29T00:00:00Z"));
      repo.insertArticleRevision(before.id(), next);

      Article after = repo.findArticleById(documentId).orElseThrow();
      assertEquals(documentId, before.id().value());
      assertEquals(documentId, after.id().value());
      assertEquals(2, after.currentRevision().revisionNumber());
      assertEquals("hash-v2", after.currentRevision().contentHash());
      assertEquals("Manual title", after.metadata().title().orElseThrow());
      assertEquals(2, repo.findRevisionHistory(documentId).current().revisionNumber());
    }
  }
}
