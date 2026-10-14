package me.asu.kb.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.adapter.document.LegacyDocumentSnapshot;
import me.asu.kb.adapter.document.LegacySourceProvenance;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitToolProfile;
import me.asu.kb.adapter.symbol.CtagsToolProfile;
import me.asu.kb.adapter.symbol.ExtractedSymbol;
import me.asu.kb.adapter.symbol.SymbolExtractionResult;
import me.asu.kb.adapter.symbol.SymbolIdentity;
import me.asu.kb.adapter.symbol.SymbolKind;
import me.asu.kb.adapter.symbol.SymbolLanguage;
import me.asu.kb.adapter.symbol.SymbolSourceRange;
import me.asu.kb.adapter.symbol.VerifiedSymbolExtraction;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.service.Article;
import me.asu.kb.service.ArticleId;
import me.asu.kb.service.ArticleMetadata;
import me.asu.kb.service.ArticleRevision;
import me.asu.kb.service.ArticleSourceKind;
import me.asu.kb.service.ArticleState;
import me.asu.kb.service.ProjectionState;
import me.asu.kb.service.ProjectionStatus;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteKnowledgeProjectionIngestionTest {
  private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
  private static final String JAVA_PATH = "src/main/java/demo/Widget.java";
  private static final String JAVA_HASH = "a".repeat(64);
  private static final String COMMIT_ID = "b".repeat(40);

  @TempDir Path tempDirectory;

  @Test
  void persistsDocumentSymbolsAndGitThenReadsThemAfterReopen() throws Exception {
    Path workspace = tempDirectory.resolve("workspace");
    java.nio.file.Files.createDirectories(workspace);
    Path database = tempDirectory.resolve("knowledge.db");

    IngestionReport report;
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      KnowledgeProjectionIngestion ingestion =
          new SqliteKnowledgeProjectionIngestion(store, Map.of("main", workspace));
      report = ingestion.ingest(IngestionRequest.of(
          "main", List.of(documentUnit(), symbolUnit(), gitUnit())));
    }

    assertEquals(List.of(IngestionStatus.SAVED, IngestionStatus.SAVED, IngestionStatus.SAVED),
        report.units().stream().map(IngestionUnitReport::status).toList());
    assertTrue(report.fullySuccessful());
    try (SqliteKnowledgeStore reopened = new SqliteKnowledgeStore(database)) {
      assertTrue(reopened.findObject("document:v1:sha256:" + sha256("doc:architecture")).isPresent());
      assertTrue(reopened.findObject("java-source:v1:sha256:" + sha256("java:widget")).isPresent());
      assertTrue(reopened.findObject("git-commit:v1:" + COMMIT_ID).isPresent());
    }
  }

  @Test
  void reportsUnchangedAndChangedInputOrProfile() throws Exception {
    Path workspace = tempDirectory.resolve("workspace");
    java.nio.file.Files.createDirectories(workspace);
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDirectory.resolve("states.db"))) {
      KnowledgeProjectionIngestion ingestion =
          new SqliteKnowledgeProjectionIngestion(store, Map.of("main", workspace));
      IngestionRequest initial = IngestionRequest.of(
          "main", List.of(documentUnit("document-hash"), symbolUnit("6.2")));

      assertEquals(List.of(IngestionStatus.SAVED, IngestionStatus.SAVED), statuses(ingestion.ingest(initial)));
      assertEquals(List.of(IngestionStatus.UNCHANGED, IngestionStatus.UNCHANGED),
          statuses(ingestion.ingest(initial)));
      assertEquals(List.of(IngestionStatus.STALE, IngestionStatus.STALE), statuses(ingestion.ingest(
          IngestionRequest.of("main", List.of(documentUnit("document-hash-2"), symbolUnit("6.3"))))));
    }
  }

  @Test
  void rejectsInvalidRequestBeforeWrites() throws Exception {
    Path workspace = tempDirectory.resolve("workspace");
    java.nio.file.Files.createDirectories(workspace);
    Path database = tempDirectory.resolve("invalid.db");
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      KnowledgeProjectionIngestion ingestion =
          new SqliteKnowledgeProjectionIngestion(store, Map.of("main", workspace));
      IngestionReport report = ingestion.ingest(
          IngestionRequest.of("unknown", List.of(documentUnit())));

      assertEquals(IngestionStatus.FAILED, report.units().get(0).status());
      assertEquals(IngestionDiagnosticCode.UNKNOWN_WORKSPACE,
          report.units().get(0).diagnostics().get(0).code());
      assertTrue(store.findObject("document:v1:sha256:" + sha256("doc:architecture")).isEmpty());
    }
  }

  @Test
  void rollsBackFailedUnitAndContinuesIndependentUnits() throws Exception {
    Path workspace = tempDirectory.resolve("workspace");
    java.nio.file.Files.createDirectories(workspace);
    Path database = tempDirectory.resolve("failure.db");
    String conflictingId = "git-commit:v1:" + COMMIT_ID;
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      KnowledgeObject input = KnowledgeObject.canonical(
          "fixture-input", ObjectType.OTHER, "input", Optional.empty(),
          SourceRef.of("fixture", "input"), Map.of(), "fixture-hash");
      KnowledgeObject conflict = KnowledgeObject.derived(
          conflictingId, ObjectType.OTHER, "conflict", Optional.empty(),
          SourceRef.of("fixture", "conflict"), Map.of(), "conflict-hash", "fixture-input",
          "fixture-generator", "1", "fixture-hash");
      store.save(new ProjectionWrite(
          List.of(ObjectWrite.canonical(input), ObjectWrite.derived(conflict, "fixture-input")),
          List.of(), List.of()));
      KnowledgeProjectionIngestion ingestion =
          new SqliteKnowledgeProjectionIngestion(store, Map.of("main", workspace));

      IngestionReport report = ingestion.ingest(
          IngestionRequest.of("main", List.of(documentUnit(), gitUnit())));

      assertEquals(List.of(IngestionStatus.SAVED, IngestionStatus.FAILED), statuses(report));
      assertEquals(IngestionDiagnosticCode.STORE_FAILED,
          report.units().get(1).diagnostics().get(0).code());
      assertEquals(ObjectType.OTHER, store.findObject(conflictingId).orElseThrow().object().type());
      assertTrue(store.findObject("document:v1:sha256:" + sha256("doc:architecture")).isPresent());
    }
  }

  @Test
  void neverPersistsRipgrepCandidatesOrInfersRelations() throws Exception {
    Path workspace = tempDirectory.resolve("workspace");
    java.nio.file.Files.createDirectories(workspace);
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDirectory.resolve("boundaries.db"))) {
      KnowledgeProjectionIngestion ingestion =
          new SqliteKnowledgeProjectionIngestion(store, Map.of("main", workspace));
      IngestionReport report = ingestion.ingest(IngestionRequest.of(
          "main", List.of(documentUnit(),
              new UnsupportedIngestionUnit("search:one", IngestionSourceCategory.RIPGREP_TEXT))));

      assertEquals(List.of(IngestionStatus.SAVED, IngestionStatus.UNSUPPORTED), statuses(report));
      String documentId = "document:v1:sha256:" + sha256("doc:architecture");
      assertTrue(store.findObject(documentId).isPresent());
      assertTrue(store.findRelationsForObject(documentId, 10).isEmpty());
      assertTrue(store.findObject("search:one").isEmpty());
    }
  }

  private static DocumentIngestionUnit documentUnit() {
    return documentUnit("document-hash");
  }

  private static DocumentIngestionUnit documentUnit(String contentHash) {
    ArticleRevision revision = new ArticleRevision(
        1, contentHash, "markdown", "docs/architecture.md", ArticleSourceKind.ROOT, NOW);
    Article article = new Article(
        new ArticleId(1), revision,
        new ArticleMetadata(Optional.of("Architecture"), Optional.empty(), List.of(), List.of()),
        ArticleState.ACTIVE,
        new ProjectionStatus(ProjectionState.INDEXED, 1, Optional.empty(), NOW));
    return new DocumentIngestionUnit(new LegacyDocumentSnapshot(
        "doc:architecture", article, "# Architecture\nBody",
        Optional.of(new LegacySourceProvenance(
            Optional.of("workspace-file"), Optional.of("docs/architecture.md"),
            Optional.of("revision-1"))),
        List.of(), Optional.empty(), Optional.empty(), Optional.of("docs/architecture.md")));
  }

  private static JavaSymbolIngestionUnit symbolUnit() {
    return symbolUnit("6.2");
  }

  private static JavaSymbolIngestionUnit symbolUnit(String version) {
    Evidence evidence = new Evidence(
        EvidenceType.STATIC_ANALYSIS,
        new SourceRef("engineering-workspace", "main:" + JAVA_PATH, Optional.of(JAVA_HASH)),
        JAVA_PATH + ":L1", 1.0d);
    ExtractedSymbol symbol = new ExtractedSymbol(
        new SymbolIdentity("demo.Widget", false), SymbolKind.CLASS, SymbolLanguage.JAVA,
        "Widget", Optional.of("demo.Widget"), Optional.empty(), Optional.empty(),
        Optional.empty(), JAVA_PATH, new SymbolSourceRange(1, Optional.of(3)), evidence);
    SymbolExtractionResult result = new SymbolExtractionResult(
        List.of(symbol), new CtagsToolProfile("ctags-java-json-v1", Optional.of(version)),
        List.of(), true);
    return new JavaSymbolIngestionUnit(
        "java:widget", JAVA_PATH, new VerifiedSymbolExtraction(result, Map.of(JAVA_PATH, JAVA_HASH)));
  }

  private static GitCommitIngestionUnit gitUnit() {
    Evidence evidence = new Evidence(
        EvidenceType.VERSION_CONTROL,
        new SourceRef("engineering-workspace", "main", Optional.of(COMMIT_ID)),
        "commit:" + COMMIT_ID, 1.0d);
    GitCommitEvidence commit = new GitCommitEvidence(
        COMMIT_ID, List.of(), "Author", "author@example.invalid", NOW, "Committer",
        "committer@example.invalid", NOW, "Initial commit", List.of(), evidence);
    return new GitCommitIngestionUnit(
        "git:" + COMMIT_ID, commit,
        new GitToolProfile("git-local-readonly-v1", Optional.of("2.51"),
            Optional.of("sha1"), Optional.of(false)));
  }

  private static String sha256(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte item : digest) {
      result.append(String.format("%02x", Byte.toUnsignedInt(item)));
    }
    return result.toString();
  }

  private static List<IngestionStatus> statuses(IngestionReport report) {
    return report.units().stream().map(IngestionUnitReport::status).toList();
  }
}
