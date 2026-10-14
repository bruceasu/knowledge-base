package me.asu.kb.adapter.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.ingest.Chunk;
import me.asu.kb.service.Article;
import me.asu.kb.service.ArticleId;
import me.asu.kb.service.ArticleMetadata;
import me.asu.kb.service.ArticleRevision;
import me.asu.kb.service.ArticleSourceKind;
import me.asu.kb.service.ArticleState;
import me.asu.kb.service.ProjectionState;
import me.asu.kb.service.ProjectionStatus;
import org.junit.jupiter.api.Test;

class LegacyDocumentKnowledgeAdapterTest {

  private static final LegacyDocumentKnowledgeAdapter ADAPTER =
      new LegacyDocumentKnowledgeAdapter();
  private static final Instant CREATED_AT = Instant.parse("2026-09-19T00:00:00Z");

  @Test
  void mapsNestedHeadingsWithParentLinksTitlePathsAndSourceRanges() {
    String content =
        "# Overview\nintro\n## Detail\ndetail\n### Leaf\nleaf\n# Other\ntail";

    DocumentKnowledgeProjection projection = ADAPTER.adapt(snapshot(content, "source-hash-1"));

    assertEquals(ObjectType.DOCUMENT, projection.document().orElseThrow().type());
    assertEquals(new SourceRange(1, 8), projection.documentSourceRange().orElseThrow());
    assertEquals(4, projection.sections().size());
    DocumentSection overview = projection.sections().get(0);
    DocumentSection detail = projection.sections().get(1);
    DocumentSection leaf = projection.sections().get(2);
    DocumentSection other = projection.sections().get(3);
    assertEquals(projection.document().orElseThrow().id(), overview.parentObjectId());
    assertEquals(overview.object().id(), detail.parentObjectId());
    assertEquals(detail.object().id(), leaf.parentObjectId());
    assertEquals(projection.document().orElseThrow().id(), other.parentObjectId());
    assertEquals(List.of("Overview", "Detail", "Leaf"), leaf.titlePath());
    assertEquals(new SourceRange(1, 6), overview.sourceRange());
    assertEquals(new SourceRange(3, 6), detail.sourceRange());
    assertEquals(new SourceRange(5, 6), leaf.sourceRange());
    assertEquals(new SourceRange(7, 8), other.sourceRange());
  }

  @Test
  void ignoresFencedPseudoHeadingsAndRetainsUnsupportedSyntaxAsText() {
    String content =
        "# Root\n```java\n## not a heading\n```\n## Actual\nbody\nHeading\n-----";

    DocumentKnowledgeProjection projection = ADAPTER.adapt(snapshot(content, "source-hash-1"));

    assertEquals(List.of("Root", "Actual"), titles(projection));
    assertTrue(projection.document().orElseThrow().content().orElseThrow().contains("## not a heading"));
    assertTrue(diagnosticCodes(projection).contains(AdapterDiagnosticCode.UNSUPPORTED_HEADING_SYNTAX));
  }

  @Test
  void reportsSkippedLevelsWithoutInventingIntermediateSections() {
    String content = "# Root\n### Deep\ntext";

    DocumentKnowledgeProjection projection = ADAPTER.adapt(snapshot(content, "source-hash-1"));

    assertEquals(List.of("Root", "Deep"), titles(projection));
    assertEquals(projection.sections().get(0).object().id(), projection.sections().get(1).parentObjectId());
    assertTrue(diagnosticCodes(projection).contains(AdapterDiagnosticCode.SKIPPED_HEADING_LEVEL));
  }

  @Test
  void preservesNoHeadingAndEmptyDocumentsWithoutSyntheticSections() {
    DocumentKnowledgeProjection noHeading = ADAPTER.adapt(snapshot("plain text", "source-hash-1"));
    DocumentKnowledgeProjection empty = ADAPTER.adapt(snapshot("", "source-hash-empty"));

    assertTrue(noHeading.sections().isEmpty());
    assertEquals(Optional.of("plain text"), noHeading.document().orElseThrow().content());
    assertTrue(empty.sections().isEmpty());
    assertEquals(Optional.empty(), empty.document().orElseThrow().content());
    assertTrue(empty.documentSourceRange().isEmpty());
  }

  @Test
  void keepsDocumentIdentityAcrossRevisionChangesAndDisambiguatesRepeatedHeadings() {
    String firstContent = "# Root\n## Repeat\nfirst\n## Repeat\nsecond";
    String changedContent = "# Root\n## Repeat\nchanged\n## Repeat\nsecond";

    DocumentKnowledgeProjection first = ADAPTER.adapt(snapshot(firstContent, "source-hash-1"));
    DocumentKnowledgeProjection repeated = ADAPTER.adapt(snapshot(firstContent, "source-hash-1"));
    DocumentKnowledgeProjection changed = ADAPTER.adapt(snapshot(changedContent, "source-hash-2"));

    assertEquals(first.document().orElseThrow().id(), changed.document().orElseThrow().id());
    assertNotEquals(
        first.document().orElseThrow().contentHash(), changed.document().orElseThrow().contentHash());
    assertNotEquals(first.sections().get(1).object().id(), first.sections().get(2).object().id());
    assertEquals(first.sections().get(1).object().id(), repeated.sections().get(1).object().id());
    assertEquals(first.sections().get(2).object().id(), repeated.sections().get(2).object().id());
  }

  @Test
  void mapsDerivedChunksAndUsesOnlyExplicitOrConfirmedArtifactClassification() {
    String content = "# Root\nbody";
    LegacyDocumentSnapshot sourceClassified =
        snapshot(
            content,
            "source-hash-1",
            List.of(new Chunk(3, "body chunk", "body", 2, 2)),
            Optional.empty(),
            Optional.of(OpenSpecArtifactKind.DESIGN),
            Optional.of("openspec/changes/example/design.md"));
    LegacyDocumentSnapshot pathClassified =
        snapshot(
            "# Design words do not decide the type",
            "source-hash-2",
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("openspec/changes/example/specs/example/spec.md"));

    DocumentKnowledgeProjection sourceProjection = ADAPTER.adapt(sourceClassified);
    DocumentKnowledgeProjection pathProjection = ADAPTER.adapt(pathClassified);
    DerivedChunkProjection chunk = sourceProjection.derivedChunks().get(0);

    assertEquals(ObjectType.DESIGN, sourceProjection.document().orElseThrow().type());
    assertEquals(ObjectType.REQUIREMENT, pathProjection.document().orElseThrow().type());
    assertEquals(KnowledgeClass.DERIVED, chunk.object().knowledgeClass());
    assertEquals(
        sourceProjection.sections().get(0).object().id(), chunk.object().canonicalSourceId().orElseThrow());
    assertEquals(Optional.of("legacy-document-knowledge-adapter"), chunk.object().generatorId());
    assertEquals(Optional.of("1"), chunk.object().generatorVersion());
    assertEquals(Optional.of("source-hash-1"), chunk.object().inputContentHash());
    assertTrue(diagnosticCodes(sourceProjection).contains(AdapterDiagnosticCode.LEGACY_CHUNK_GENERATOR_UNKNOWN));
  }

  @Test
  void reportsIncompleteSourceWithoutFabricatingCoreObjects() {
    LegacyDocumentSnapshot snapshot =
        new LegacyDocumentSnapshot(
            "workspace:demo/document:architecture",
            article("source-hash-1"),
            "# Root",
            Optional.of(
                new LegacySourceProvenance(
                    Optional.empty(), Optional.of("docs/architecture.md"), Optional.empty())),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    DocumentKnowledgeProjection projection = ADAPTER.adapt(snapshot);

    assertTrue(projection.document().isEmpty());
    assertTrue(projection.sections().isEmpty());
    assertTrue(projection.derivedChunks().isEmpty());
    assertTrue(diagnosticCodes(projection).contains(AdapterDiagnosticCode.MISSING_SOURCE_SYSTEM));
  }

  private static LegacyDocumentSnapshot snapshot(String content, String contentHash) {
    return snapshot(
        content,
        contentHash,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.of("docs/architecture.md"));
  }

  private static LegacyDocumentSnapshot snapshot(
      String content,
      String contentHash,
      List<Chunk> chunks,
      Optional<LegacyGeneratorProvenance> legacyGenerator,
      Optional<OpenSpecArtifactKind> explicitKind,
      Optional<String> repositoryRelativePath) {
    return new LegacyDocumentSnapshot(
        "workspace:demo/document:architecture",
        article(contentHash),
        content,
        Optional.of(
            new LegacySourceProvenance(
                Optional.of("workspace-file"),
                Optional.of("docs/architecture.md"),
                Optional.of("revision-1"))),
        chunks,
        legacyGenerator,
        explicitKind,
        repositoryRelativePath);
  }

  private static Article article(String contentHash) {
    ArticleRevision revision =
        new ArticleRevision(
            1,
            contentHash,
            "markdown",
            "docs/architecture.md",
            ArticleSourceKind.ROOT,
            CREATED_AT);
    return new Article(
        new ArticleId(7),
        revision,
        new ArticleMetadata(Optional.of("Architecture"), Optional.of("System design"), List.of("java"), List.of("architecture")),
        ArticleState.ACTIVE,
        new ProjectionStatus(ProjectionState.INDEXED, 1, Optional.empty(), CREATED_AT));
  }

  private static List<String> titles(DocumentKnowledgeProjection projection) {
    return projection.sections().stream().map(section -> section.object().title()).toList();
  }

  private static Set<AdapterDiagnosticCode> diagnosticCodes(DocumentKnowledgeProjection projection) {
    return projection.diagnostics().stream()
        .map(AdapterDiagnostic::code)
        .collect(Collectors.toSet());
  }
}
