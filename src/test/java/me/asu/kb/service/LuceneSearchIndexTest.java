package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneSearchIndexTest {

  @TempDir
  Path tempDir;

  @Test
  void metadataAndContentFieldsAreProjectedForSearch() throws Exception {
    try (KnowledgeRepository repository = new KnowledgeRepository(tempDir.resolve("kb.sqlite"));
        LuceneSearchIndex index = new LuceneSearchIndex(tempDir.resolve("index"))) {
      long documentId = repository.upsertDocument("/root", "/root/guide.md", "markdown", "UTF-8", "hash");
      repository.insertChunk(documentId, 0, "Chunk heading", "body content", 1, 1);
      repository.upsertDocumentMetadata(documentId, "title", "Metadata title", "source", null);
      repository.upsertDocumentMetadata(documentId, "summary", "Metadata summary", "source", null);
      repository.upsertDocumentTag(documentId, "metadata-tag", "source");
      repository.upsertDocumentKeyword(documentId, "metadata-keyword", "source");
      KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId).orElseThrow();
      KnowledgeRepository.ChunkRow chunk = repository.findChunksByDocumentId(documentId).get(0);
      KnowledgeRepository.EffectiveDocumentMetadata metadata = repository.findEffectiveDocumentMetadata(documentId);
      index.addChunk(document, chunk, metadata);
      index.commit();

      assertFalse(index.search("Metadata title", 5, null).isEmpty());
      assertFalse(index.search("metadata-tag", 5, null).isEmpty());
      assertFalse(index.search("metadata-keyword", 5, null).isEmpty());
      assertFalse(index.search("Metadata summary", 5, null).isEmpty());
      assertFalse(index.search("body content", 5, null).isEmpty());
      assertFalse(index.search("body content", 5, null, java.util.List.of("METADATA-TAG")).isEmpty());
      assertTrue(index.search("body content", 5, null, java.util.List.of("missing-tag")).isEmpty());
    }
  }

  @Test
  void uncommittedRebuildChangesNeverReplaceTheCommittedSearchSnapshot() throws Exception {
    try (KnowledgeRepository repository = new KnowledgeRepository(tempDir.resolve("kb.sqlite"));
        LuceneSearchIndex index = new LuceneSearchIndex(tempDir.resolve("index"))) {
      long documentId = repository.upsertDocument("/root", "/root/guide.md", "markdown", "UTF-8", "hash");
      KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId).orElseThrow();
      repository.insertChunk(documentId, 0, "Old", "committedtoken", 1, 1);
      KnowledgeRepository.ChunkRow oldChunk = repository.findChunksByDocumentId(documentId).get(0);
      index.addChunk(document, oldChunk, repository.findEffectiveDocumentMetadata(documentId));
      index.commit();

      index.clear();
      repository.insertChunk(documentId, 1, "New", "uncommittedtoken", 2, 2);
      KnowledgeRepository.ChunkRow newChunk = repository.findChunksByDocumentId(documentId).get(1);
      index.addChunk(document, newChunk, repository.findEffectiveDocumentMetadata(documentId));
      assertFalse(index.search("committedtoken", 5, null).isEmpty());
      assertTrue(index.search("uncommittedtoken", 5, null).isEmpty());

      index.discardUncommittedChanges();
      assertFalse(index.search("committedtoken", 5, null).isEmpty());
      index.addChunk(document, newChunk, repository.findEffectiveDocumentMetadata(documentId));
      index.commit();
      assertFalse(index.search("uncommittedtoken", 5, null).isEmpty());
    }
  }

  @Test
  void inFlightSearchRetainsReaderWhileNewSnapshotPublishes() throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (KnowledgeRepository repository = new KnowledgeRepository(tempDir.resolve("reader.sqlite"));
        LuceneSearchIndex index = new LuceneSearchIndex(tempDir.resolve("reader-index"), () -> {}, () -> {
          acquired.countDown();
          if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        })) {
      long id = repository.upsertDocument("/root", "/root/reader.md", "markdown", "UTF-8", "hash");
      KnowledgeRepository.DocumentRow document = repository.findDocumentById(id).orElseThrow();
      repository.insertChunk(id, 0, "Old", "oldreadertoken", 1, 1);
      index.addChunk(document, repository.findChunksByDocumentId(id).get(0), repository.findEffectiveDocumentMetadata(id));
      index.commit();
      Future<java.util.List<me.asu.kb.search.SearchHit>> oldSearch = executor.submit(() -> index.search("oldreadertoken", 5, null));
      assertTrue(acquired.await(2, TimeUnit.SECONDS));
      repository.insertChunk(id, 1, "New", "newreadertoken", 2, 2);
      index.addChunk(document, repository.findChunksByDocumentId(id).get(1), repository.findEffectiveDocumentMetadata(id));
      index.commit();
      release.countDown();
      assertFalse(oldSearch.get(2, TimeUnit.SECONDS).isEmpty());
      assertFalse(index.search("newreadertoken", 5, null).isEmpty());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }
}
