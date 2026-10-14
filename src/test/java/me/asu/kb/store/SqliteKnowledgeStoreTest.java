package me.asu.kb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.store.SqliteKnowledgeStore.IndexStateStatus;
import me.asu.kb.store.SqliteKnowledgeStore.IndexStateWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectLifecycleState;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import me.asu.kb.store.SqliteKnowledgeStore.RelationProjection;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectIndexStatus;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectGitProvenance;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceKind;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectSyncStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteKnowledgeStoreTest {
  @TempDir Path tempDir;

  @Test
  void initializesNewAndLegacyDatabasesWithoutChangingLegacyRows() throws Exception {
    Path freshDatabase = tempDir.resolve("fresh.sqlite");
    try (SqliteKnowledgeStore ignored = new SqliteKnowledgeStore(freshDatabase)) {
      assertKnowledgeSchema(freshDatabase);
    }

    Path legacyDatabase = tempDir.resolve("legacy.sqlite");
    createLegacyDatabase(legacyDatabase);
    try (SqliteKnowledgeStore ignored = new SqliteKnowledgeStore(legacyDatabase)) {
      assertKnowledgeSchema(legacyDatabase);
    }

    try (Connection connection = connection(legacyDatabase)) {
      assertEquals(1, rowCount(connection, "documents"));
      assertEquals(1, rowCount(connection, "articles"));
      assertEquals(1, rowCount(connection, "article_revisions"));
      assertEquals(1, rowCount(connection, "chunks"));
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT path FROM documents WHERE id = 1");
          ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        assertEquals("legacy.md", result.getString(1));
      }
    }
  }

  @Test
  void savesReadsAndReopensAnEvidenceBearingProjection() throws Exception {
    Path database = tempDir.resolve("projection.sqlite");
    ProjectionWrite projection = validProjection("document-v1", List.of(evidence("line-1"), evidence("line-2")));

    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      store.save(projection);

      assertEquals("document", store.findObject("document").orElseThrow().object().id());
      assertEquals(
          "document",
          store.findObject("chunk").orElseThrow().inputObjectId().orElseThrow());
      assertEquals(1, store.findRelationsForObject("document", 10).size());
      assertEquals(2, store.findRelationsForObject("document", 10).get(0).evidence().size());
      assertEquals(
          IndexStateStatus.CURRENT,
          store.findIndexStatesForObject("chunk", 10).get(0).status());
    }

    try (SqliteKnowledgeStore reopened = new SqliteKnowledgeStore(database)) {
      assertEquals("document-v1", reopened.findObject("document").orElseThrow().object().contentHash());
      assertEquals(1, reopened.findRelationsForObject("section", 10).size());
      assertEquals(2, reopened.findRelationsForObject("section", 10).get(0).evidence().size());
      assertEquals(1, reopened.findIndexStatesForObject("chunk", 10).size());
    }
  }

  @Test
  void persistsVirtualProjectReferencesAndLifecycleStateAcrossReopen() throws Exception {
    Path database = tempDir.resolve("virtual-project.sqlite");
    String sourcePath = tempDir.resolve("project").resolve("../project").toString();
    String projectId;
    String referenceId;
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      var created = store.createVirtualProject("Project A", "project description");
      projectId = created.id();
      assertTrue(projectId.startsWith("virtual-project:v1:"));
      assertEquals(VirtualProjectIndexStatus.NOT_BUILT, created.indexStatus());
      assertEquals(VirtualProjectSyncStatus.NEVER_RUN, created.syncStatus());

      var firstReference =
          store.addVirtualProjectResource(
              projectId,
              VirtualProjectResourceKind.DIRECTORY,
              sourcePath,
              Optional.of(
                  new VirtualProjectGitProvenance(
                      tempDir.resolve("repository").toAbsolutePath().normalize().toString(),
                      "docs")));
      var replayedReference =
          store.addVirtualProjectResource(
              projectId,
              VirtualProjectResourceKind.DIRECTORY,
              sourcePath,
              firstReference.gitProvenance());
      referenceId = firstReference.id();
      assertEquals(firstReference.id(), replayedReference.id());
      assertEquals(firstReference.sourcePath(), replayedReference.sourcePath());
      assertEquals(firstReference.gitProvenance(), replayedReference.gitProvenance());
      assertEquals(1, store.findVirtualProjectResources(projectId, 10).size());

      var updated =
          store.updateVirtualProjectState(
              projectId,
              VirtualProjectIndexStatus.CURRENT,
              VirtualProjectSyncStatus.SUCCEEDED,
              Optional.of("2026-09-26T08:00:00Z"),
              Optional.of("2026-09-26T08:01:00Z"),
              Optional.empty());
      assertEquals(VirtualProjectIndexStatus.CURRENT, updated.indexStatus());
      assertEquals(VirtualProjectSyncStatus.SUCCEEDED, updated.syncStatus());
    }

    try (SqliteKnowledgeStore reopened = new SqliteKnowledgeStore(database)) {
      var project = reopened.findVirtualProject(projectId).orElseThrow();
      assertEquals("Project A", project.name());
      assertEquals(VirtualProjectSyncStatus.SUCCEEDED, project.syncStatus());
      assertEquals(
          Path.of(sourcePath).toAbsolutePath().normalize().toString(),
          reopened.findVirtualProjectResources(projectId, 10).get(0).sourcePath());
      assertEquals(
          "docs",
          reopened
              .findVirtualProjectResources(projectId, 10)
              .get(0)
              .gitProvenance()
              .orElseThrow()
              .relativePath());
      assertTrue(reopened.deleteVirtualProjectResource(projectId, referenceId));
      assertTrue(reopened.findVirtualProjectResources(projectId, 10).isEmpty());
      assertTrue(reopened.deleteVirtualProject(projectId));
      assertTrue(reopened.findVirtualProject(projectId).isEmpty());
    }
  }

  @Test
  void rejectsInvalidRelationsAndDerivedInputWithoutPartialRows() throws Exception {
    Path database = tempDir.resolve("invalid.sqlite");
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      KnowledgeObject document = canonical("document", ObjectType.DOCUMENT, "document-v1", "r1");
      ProjectionWrite evidenceLess =
          new ProjectionWrite(
              List.of(ObjectWrite.canonical(document)),
              List.of(new RelationProjection("document", RelationType.DESCRIBES, "document", List.of())),
              List.of(IndexStateWrite.current("bm25", "1", "document", "document", "document-v1")));
      assertThrows(IllegalArgumentException.class, () -> store.save(evidenceLess));
      assertTrue(store.findObject("document").isEmpty());

      ProjectionWrite missingEndpoint =
          new ProjectionWrite(
              List.of(ObjectWrite.canonical(document)),
              List.of(
                  new RelationProjection(
                      "document", RelationType.DESCRIBES, "missing", List.of(evidence("line-1")))),
              List.of());
      assertThrows(IllegalArgumentException.class, () -> store.save(missingEndpoint));
      assertTrue(store.findObject("document").isEmpty());

      KnowledgeObject derivedWithMissingSource =
          derived("chunk", "missing-section", "document-v1", "chunk-v1");
      ProjectionWrite missingDerivedInput =
          new ProjectionWrite(
              List.of(
                  ObjectWrite.canonical(document),
                  new ObjectWrite(derivedWithMissingSource, Optional.empty())),
              List.of(),
              List.of());
      assertThrows(IllegalArgumentException.class, () -> store.save(missingDerivedInput));
      assertTrue(store.findObject("document").isEmpty());
      assertTrue(store.findObject("chunk").isEmpty());
    }
  }

  @Test
  void rollsBackAWriteFailureAfterObjectAndRelationStatements() throws Exception {
    Path database = tempDir.resolve("rollback.sqlite");
    ProjectionWrite projection = validProjection("document-v1", List.of(evidence("line-1")));

    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
        statement.execute(
            """
            CREATE TRIGGER fail_knowledge_evidence
            BEFORE INSERT ON knowledge_evidence
            BEGIN
              SELECT RAISE(ABORT, 'injected evidence failure');
            END
            """);
      }

      assertThrows(SQLException.class, () -> store.save(projection));
      assertTrue(store.findObject("document").isEmpty());
      assertTrue(store.findRelationsForObject("document", 10).isEmpty());
      assertTrue(store.findIndexStatesForObject("chunk", 10).isEmpty());

      try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER fail_knowledge_evidence");
      }
      store.save(projection);
      assertTrue(store.findObject("document").isPresent());
    }
  }

  @Test
  void replaysIdempotentlyAndMarksDependentProjectionStaleOnCanonicalChange() throws Exception {
    Path database = tempDir.resolve("replay.sqlite");
    Evidence firstEvidence = evidence("line-1");
    Evidence secondEvidence = evidence("line-2");
    ProjectionWrite first = validProjection("document-v1", List.of(firstEvidence, secondEvidence));
    ProjectionWrite replayed = validProjection("document-v1", List.of(secondEvidence, firstEvidence));

    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      store.save(first);
      store.save(replayed);
      try (Connection connection = connection(database)) {
        assertEquals(3, rowCount(connection, "knowledge_object"));
        assertEquals(1, rowCount(connection, "knowledge_relation"));
        assertEquals(2, rowCount(connection, "knowledge_evidence"));
        assertEquals(1, rowCount(connection, "knowledge_index_state"));
      }

      store.save(
          new ProjectionWrite(
              List.of(ObjectWrite.canonical(canonical("document", ObjectType.DOCUMENT, "document-v2", "r2"))),
              List.of(),
              List.of()));

      assertEquals(
          ObjectLifecycleState.STALE,
          store.findObject("chunk").orElseThrow().lifecycleState());
      assertEquals(
          IndexStateStatus.STALE,
          store.findIndexStatesForObject("chunk", 10).get(0).status());

      IndexStateWrite failure =
          IndexStateWrite.failed(
              "bm25", "1", "chunk", "document", "document-v2", "projection worker failed");
      store.recordProjectionFailure(failure);
      assertEquals(
          IndexStateStatus.FAILED,
          store.findIndexStatesForObject("chunk", 10).get(0).status());
      assertEquals(
          "projection worker failed",
          store.findIndexStatesForObject("chunk", 10).get(0).failureDetail().orElseThrow());

      KnowledgeObject refreshedChunk = derived("chunk", "section", "document-v2", "chunk-v2");
      store.save(
          new ProjectionWrite(
              List.of(ObjectWrite.derived(refreshedChunk, "document")),
              List.of(),
              List.of(IndexStateWrite.current("bm25", "1", "chunk", "document", "document-v2"))));
      assertEquals(
          ObjectLifecycleState.CURRENT,
          store.findObject("chunk").orElseThrow().lifecycleState());
      assertEquals(
          IndexStateStatus.CURRENT,
          store.findIndexStatesForObject("chunk", 10).get(0).status());
      assertFalse(store.findIndexStatesForObject("chunk", 10).get(0).failureDetail().isPresent());
    }
  }

  private static ProjectionWrite validProjection(String documentHash, List<Evidence> evidence) {
    KnowledgeObject document = canonical("document", ObjectType.DOCUMENT, documentHash, "r1");
    KnowledgeObject section = canonical("section", ObjectType.SECTION, "section-v1", "r1");
    KnowledgeObject chunk = derived("chunk", "section", documentHash, "chunk-v1");
    return new ProjectionWrite(
        List.of(
            ObjectWrite.canonical(document),
            ObjectWrite.canonical(section),
            ObjectWrite.derived(chunk, "document")),
        List.of(new RelationProjection("document", RelationType.DESCRIBES, "section", evidence)),
        List.of(IndexStateWrite.current("bm25", "1", "chunk", "document", documentHash)));
  }

  private static KnowledgeObject canonical(
      String id, ObjectType type, String contentHash, String revision) {
    return KnowledgeObject.canonical(
        id,
        type,
        id + " title",
        Optional.of(id + " content " + contentHash),
        new SourceRef("repository", "docs/" + id + ".md", Optional.of(revision)),
        Map.of("kind", type.name()),
        contentHash);
  }

  private static KnowledgeObject derived(
      String id, String canonicalSourceId, String inputContentHash, String contentHash) {
    return KnowledgeObject.derived(
        id,
        ObjectType.CHUNK,
        id + " title",
        Optional.of(id + " content " + contentHash),
        new SourceRef("repository", "docs/" + id + ".md", Optional.of("r1")),
        Map.of("generator", "test"),
        contentHash,
        canonicalSourceId,
        "test-generator",
        "1",
        inputContentHash);
  }

  private static Evidence evidence(String location) {
    return new Evidence(
        EvidenceType.SOURCE_TEXT,
        new SourceRef("repository", "docs/document.md", Optional.of("r1")),
        location,
        0.9d);
  }

  private static void createLegacyDatabase(Path database) throws SQLException {
    try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE documents(id INTEGER PRIMARY KEY, path TEXT NOT NULL)");
      statement.execute("CREATE TABLE articles(article_id INTEGER PRIMARY KEY, document_id INTEGER NOT NULL)");
      statement.execute(
          "CREATE TABLE article_revisions(article_id INTEGER NOT NULL, revision_number INTEGER NOT NULL, content_hash TEXT NOT NULL)");
      statement.execute("CREATE TABLE chunks(id INTEGER PRIMARY KEY, document_id INTEGER NOT NULL, content TEXT NOT NULL)");
      statement.execute("INSERT INTO documents(id, path) VALUES(1, 'legacy.md')");
      statement.execute("INSERT INTO articles(article_id, document_id) VALUES(1, 1)");
      statement.execute(
          "INSERT INTO article_revisions(article_id, revision_number, content_hash) VALUES(1, 1, 'legacy-hash')");
      statement.execute("INSERT INTO chunks(id, document_id, content) VALUES(1, 1, 'legacy chunk')");
    }
  }

  private static void assertKnowledgeSchema(Path database) throws SQLException {
    try (Connection connection = connection(database)) {
      assertTrue(tableExists(connection, "knowledge_object"));
      assertTrue(tableExists(connection, "knowledge_relation"));
      assertTrue(tableExists(connection, "knowledge_evidence"));
      assertTrue(tableExists(connection, "knowledge_index_state"));
      assertTrue(tableExists(connection, "virtual_project"));
      assertTrue(tableExists(connection, "virtual_project_resource"));
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
        statement.setString(1, "engineering-knowledge-v1");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
        }
      }
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
        statement.setString(1, "virtual-projects-v1");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
        }
      }
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
        statement.setString(1, "virtual-project-git-provenance-v1");
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next());
        }
      }
    }
  }

  private static boolean tableExists(Connection connection, String tableName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
      statement.setString(1, tableName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static long rowCount(Connection connection, String tableName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static Connection connection(Path database) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
  }
}
