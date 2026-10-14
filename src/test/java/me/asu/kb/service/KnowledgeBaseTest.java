package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class KnowledgeBaseTest {

  @TempDir
  Path tempDir;

  @Test
  void ingestSearchAndAskWorkForTextFiles() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("aws-notes"));
    Path file = docsDir.resolve("guide.md");
    Files.writeString(
        file,
        "# Guide\n\nLucene indexes local knowledge.\n\nIt supports search and ask.",
        StandardCharsets.UTF_8);

    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      knowledgeBase.sync(docsDir);

      List<SearchHit> hits = knowledgeBase.search("Lucene", 5);
      assertFalse(hits.isEmpty());
      assertTrue(hits.get(0).snippet().contains("Lucene"));
      assertTrue(hits.get(0).summary() != null && !hits.get(0).summary().isBlank());
      assertTrue(hits.get(0).tags().contains("markdown"));
      assertTrue(hits.get(0).tags().contains("doc"));
      assertTrue(hits.get(0).tags().contains("aws"));
      assertTrue(hits.get(0).tags().contains("note"));

      AskResponse response = knowledgeBase.ask("What does Lucene do?", 5);
      assertTrue(response.answer().contains("ANSWER:"));
      assertTrue(response.prompt().contains("Summary:"));
      assertTrue(response.prompt().contains("Tags:"));
      assertFalse(response.sources().isEmpty());
      assertEquals(hits.get(0).documentId(), response.sources().get(0).documentId());
      assertEquals(file.toAbsolutePath().normalize().toString(), response.sources().get(0).path());
      assertTrue(response.sources().get(0).tags().contains("markdown"));
    } finally {
      knowledgeBase.close();
    }
  }

  @Test
  void rebuildRestoresSearchIndexFromDatabase() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path file = docsDir.resolve("rebuild.md");
    Files.writeString(file, "Lucene rebuild keeps data searchable.", StandardCharsets.UTF_8);

    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      knowledgeBase.sync(docsDir);
    } finally {
      knowledgeBase.close();
    }

    try (KnowledgeRepository repo = new KnowledgeRepository(tempDir.resolve("data/kb.sqlite"))) {
      long documentId =
          repo.findDocumentByPath(file.toAbsolutePath().normalize().toString()).orElseThrow().id();
      repo.deleteTagsByDocumentId(documentId);
    }

    KnowledgeBase rebuilt = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      rebuilt.rebuild();

      List<SearchHit> hits = rebuilt.search("rebuild", 5);
      assertFalse(hits.isEmpty());
      assertEquals(file.toAbsolutePath().normalize().toString(), hits.get(0).path());
      assertTrue(hits.get(0).tags().contains("markdown"));
    } finally {
      rebuilt.close();
    }
  }

  @Test
  void syncChunksHtmlAndOrgByStructure() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path html = docsDir.resolve("guide.html");
    Path org = docsDir.resolve("notes.org");
    Files.writeString(
        html,
        """
        <html>
          <body>
            <h1>Guide</h1>
            <p>Intro paragraph.</p>
            <h2>Setup</h2>
            <p>Step one.</p>
          </body>
        </html>
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        org,
        """
        * Guide

        Intro paragraph.

        ** Setup

        Step one.
        """,
        StandardCharsets.UTF_8);

    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      knowledgeBase.sync(docsDir);

      List<SearchHit> htmlHits = knowledgeBase.search("Step one", 10);
      assertTrue(
          htmlHits.stream()
              .anyMatch(
                  hit ->
                      hit.path().equals(html.toAbsolutePath().normalize().toString())
                          && "Setup".equals(hit.title())));

      List<SearchHit> orgHits = knowledgeBase.search("Intro paragraph", 10);
      assertTrue(
          orgHits.stream()
              .anyMatch(
                  hit ->
                      hit.path().equals(org.toAbsolutePath().normalize().toString())
                          && "Guide".equals(hit.title())));
    } finally {
      knowledgeBase.close();
    }
  }

  @Test
  void detectsEncodingCorrectly() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("encoding-docs"));
    Path file = docsDir.resolve("japanese.txt");
    String text = "こんにちは、世界！\nLuceneはローカルの知識をインデックス化します。";
    byte[] sjisBytes = text.getBytes(java.nio.charset.Charset.forName("Shift_JIS"));
    Files.write(file, sjisBytes);

    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      knowledgeBase.sync(docsDir);

      List<SearchHit> hits = knowledgeBase.search("Lucene", 5);
      assertFalse(hits.isEmpty());
      assertTrue(hits.get(0).snippet().contains("Lucene"));
    } finally {
      knowledgeBase.close();
    }
  }

  @Test
  void syncReplaysPendingProjectionBatchesInDurableOrder() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("projection-docs"));
    Path file = docsDir.resolve("projection.txt");
    Files.writeString(file, "projection batch recovery", StandardCharsets.UTF_8);

    KnowledgeBase initial = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      initial.sync(docsDir);
    } finally {
      initial.close();
    }

    String normalizedPath = file.toAbsolutePath().normalize().toString();
    try (KnowledgeRepository repository = new KnowledgeRepository(tempDir.resolve("data/kb.sqlite"))) {
      String checkpoint = repository.checkpoint("business").orElseThrow();
      repository.createProjectionBatch(checkpoint, List.of(normalizedPath));
      assertEquals(1, repository.pendingProjectionBatchCount());
    }

    KnowledgeBase recovered = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      recovered.sync(docsDir);
      assertEquals(0, recovered.pendingProjectionBatchCount());
      assertFalse(recovered.search("recovery", 5).isEmpty());
    } finally {
      recovered.close();
    }
  }

  @Test
  void contentSavePersistsFileBeforeReturningIndexedProjectionState() throws Exception {
    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      KnowledgeBase.DocumentView created = knowledgeBase.createDocument(
          "editable.md", "# Draft\n\noriginal", "Editable", List.of("draft"), List.of(), null);
      KnowledgeBase.DocumentView saved = knowledgeBase.saveDocument(
          created.id(), "# Draft\n\nupdated", created.contentHash(), "Editable", List.of("draft"),
          List.of("revision"), "Updated summary");

      assertEquals("# Draft\n\nupdated", Files.readString(Path.of(saved.path())));
      assertEquals(2, saved.currentRevision());
      assertEquals("indexed", saved.projectionState());
      assertEquals("Editable", saved.metadata().title().orElseThrow().value());
      assertTrue(saved.metadata().keywords().stream()
          .anyMatch(keyword -> "revision".equals(keyword.value())));
    } finally {
      knowledgeBase.close();
    }
  }

  @Test
  void detachFiltersSearchWhilePurgeDeletesDocuments() throws Exception {
    Path docsDir = Files.createDirectories(tempDir.resolve("root-docs"));
    Path file = docsDir.resolve("root.txt");
    Files.writeString(file, "root lifecycle visibility", StandardCharsets.UTF_8);

    KnowledgeBase knowledgeBase = new KnowledgeBase(tempDir, new EchoLlmClient());
    try {
      knowledgeBase.sync(docsDir);
      knowledgeBase.removeRoot(docsDir, KnowledgeBase.RootRemovalMode.DETACH, false);
      assertTrue(knowledgeBase.search("lifecycle", 5).isEmpty());

      knowledgeBase.sync(docsDir);
      knowledgeBase.removeRoot(docsDir, KnowledgeBase.RootRemovalMode.PURGE, true);
      assertTrue(knowledgeBase.search("lifecycle", 5).isEmpty());
      assertEquals("purged", knowledgeBase.roots().get(0).lifecycle());
    } finally {
      knowledgeBase.close();
    }
  }

  private static final class EchoLlmClient implements LlmClient {
    @Override
    public String generate(String prompt) {
      return "ANSWER: " + prompt;
    }
  }
}
