package me.asu.kb.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.asu.kb.llm.LlmClient;
import me.asu.kb.metadata.LlmMetadataService;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

  @Test
  void aiMetadataAcceptsValidatedFieldsAndRecordsGeneratorModel() {
    LlmMetadataService service =
        new LlmMetadataService(prompt -> "{\"title\":\"AI title\",\"summary\":\"AI summary\",\"tags\":[\"Java\",\"java\"],\"keywords\":[\"Search\"]}", "test-model");

    LlmMetadataService.AiMetadata metadata = service.generate("title", "content").orElseThrow();
    assertEquals("AI title", metadata.title().orElseThrow());
    assertEquals(List.of("java"), metadata.tags());
    assertEquals("test-model", metadata.generatorModel());
  }

  @Test
  void aiMetadataRejectsUnknownFieldsAndMalformedValues() {
    LlmClient malformed = prompt -> "{\"title\":\"ok\",\"unsafe\":\"do not persist\"}";
    assertEquals(java.util.Optional.empty(), new LlmMetadataService(malformed, "test-model").generate("title", "content"));
  }

  @Test
  void htmlMetadataUsesOnlyBoundedHeadAllowlist() {
    HtmlMetadataExtractor.HtmlMetadata metadata =
        new HtmlMetadataExtractor().extract(
            "<html><head>"
                + "<title>Document title</title>"
                + "<meta property='og:title' content='Open graph title'>"
                + "<meta name='description' content='Description'>"
                + "<meta property='og:description' content='OG description'>"
                + "<meta name='keywords' content='Java, Search, java'>"
                + "<meta property='article:tag' content='Knowledge'>"
                + "<meta name='author' content='ignored'>"
                + "</head><body><meta name='keywords' content='body-only'>"
                + "<title>Body title</title></body></html>");

    assertEquals(java.util.Optional.of("Document title"), metadata.title());
    assertEquals(java.util.Optional.of("Description"), metadata.description());
    assertEquals(java.util.List.of("java", "search"), metadata.keywords());
    assertEquals(java.util.List.of("knowledge"), metadata.tags());
  }

  @Test
  void orgMetadataExtractsOnlyFileLevelTitleAndFileTags() {
    OrgMetadataExtractor.OrgMetadata metadata =
        new OrgMetadataExtractor().extract(
            "#+TITLE:  A Useful Guide  \n"
                + "#+FILETAGS: :Java:Search:java: \n"
                + ":PROPERTIES:\n"
                + ":TITLE: ignored property\n"
                + "* Heading :ignored-heading-tag:\n"
                + "#+TAGS: ignored-declaration");

    assertEquals(java.util.Optional.of("A Useful Guide"), metadata.title());
    assertEquals(java.util.List.of("java", "search"), metadata.tags());
  }

  @Test
  void splitKeepsParagraphOrder() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks = chunker.split("First paragraph.\n\nSecond paragraph.\n\nThird.");

    assertEquals(3, chunks.size());
    assertEquals(0, chunks.get(0).chunkNo());
    assertEquals("First paragraph.", chunks.get(0).content());
    assertEquals(1, chunks.get(1).chunkNo());
    assertEquals("Second paragraph.", chunks.get(1).content());
    assertEquals(2, chunks.get(2).chunkNo());
    assertEquals("Third.", chunks.get(2).content());
  }

  @Test
  void splitMarkdownUsesHeadingsAsBoundaries() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks =
        chunker.split(
            """
            # Guide

            Intro paragraph.

            ## Setup

            Step one.

            Step two.
            """,
            TextChunker.Format.MARKDOWN);

    assertEquals(2, chunks.size());
    assertEquals("Guide", chunks.get(0).title());
    assertEquals("# Guide\n\nIntro paragraph.", chunks.get(0).content());
    assertEquals("Setup", chunks.get(1).title());
    assertEquals("## Setup\n\nStep one.\n\nStep two.", chunks.get(1).content());
  }

  @Test
  void splitMarkdownKeepsFenceContentInsideTheSameChunk() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks =
        chunker.split(
            """
            # Guide

            ```java
            # not a heading
            System.out.println("hello");
            ```

            After code.
            """,
            TextChunker.Format.MARKDOWN);

    assertEquals(1, chunks.size());
    assertEquals("Guide", chunks.get(0).title());
    assertEquals(
        """
        # Guide

        ```java
        # not a heading
        System.out.println("hello");
        ```

        After code.
        """.stripTrailing(),
        chunks.get(0).content());
  }

  @Test
  void splitMarkdownUsesSetextHeadingsAsBoundaries() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks =
        chunker.split(
            """
            Guide
            =====

            Intro paragraph.

            Setup
            -----

            Step one.
            """,
            TextChunker.Format.MARKDOWN);

    assertEquals(2, chunks.size());
    assertEquals("Guide", chunks.get(0).title());
    assertEquals("Guide\n=====\n\nIntro paragraph.", chunks.get(0).content());
    assertEquals("Setup", chunks.get(1).title());
    assertEquals("Setup\n-----\n\nStep one.", chunks.get(1).content());
  }

  @Test
  void splitHtmlUsesBlockBoundaries() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks =
        chunker.split(
            """
            <html>
              <body>
                <h1>Guide</h1>
                <p>Intro paragraph.</p>
                <h2>Setup</h2>
                <p>Step one.</p>
                <p>Step two.</p>
              </body>
            </html>
            """,
            TextChunker.Format.HTML);

    assertEquals(2, chunks.size());
    assertEquals("Guide", chunks.get(0).title());
    assertEquals("Guide\nIntro paragraph.", chunks.get(0).content());
    assertEquals("Setup", chunks.get(1).title());
    assertEquals("Setup\nStep one.\nStep two.", chunks.get(1).content());
  }

  @Test
  void splitOrgUsesSectionHeadingsAsBoundaries() {
    TextChunker chunker = new TextChunker();

    List<Chunk> chunks =
        chunker.split(
            """
            * Guide

            Intro paragraph.

            ** Setup

            Step one.

            Step two.
            """,
            TextChunker.Format.ORG);

    assertEquals(2, chunks.size());
    assertEquals("Guide", chunks.get(0).title());
    assertEquals("* Guide\n\nIntro paragraph.", chunks.get(0).content());
    assertEquals("Setup", chunks.get(1).title());
    assertEquals("** Setup\n\nStep one.\n\nStep two.", chunks.get(1).content());
  }
}
