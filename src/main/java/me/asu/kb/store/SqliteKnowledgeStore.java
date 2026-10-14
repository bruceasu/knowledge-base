package me.asu.kb.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.Relation;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;

/**
 * Isolated SQLite persistence seam for engineering knowledge projections.
 *
 * <p>This component owns only the additive knowledge schema. It deliberately does not route
 * legacy article, revision, chunk, Lucene, HTTP, or CLI calls through this store.
 */
public final class SqliteKnowledgeStore implements AutoCloseable {
  private static final String MIGRATION_VERSION = "engineering-knowledge-v1";
  private static final String VIRTUAL_PROJECT_MIGRATION_VERSION = "virtual-projects-v1";
  private static final String VIRTUAL_PROJECT_GIT_PROVENANCE_MIGRATION_VERSION =
      "virtual-project-git-provenance-v1";
  private static final String CURRENT = "CURRENT";
  private static final String STALE = "STALE";
  private static final String FAILED = "FAILED";

  private final String jdbcUrl;
  private final Connection writeConnection;
  private boolean closed;

  /** Opens the Store and applies its forward-only, repeat-safe additive migration. */
  public SqliteKnowledgeStore(java.nio.file.Path dbFile) throws SQLException {
    Objects.requireNonNull(dbFile, "dbFile");
    this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    this.writeConnection = DriverManager.getConnection(jdbcUrl);
    enableForeignKeys(writeConnection);
    migrate();
  }

  /**
   * Atomically validates and persists one candidate knowledge projection.
   *
   * <p>A write never deletes omitted relations or evidence. Replays of the same stable IDs and
   * evidence are idempotent; evidence supplied for the same logical relation is appended after
   * deterministic de-duplication.
   */
  public void save(ProjectionWrite projection) throws SQLException {
    save(projection, List.of());
  }

  /** Atomically saves a projection and stales older profiles for the same canonical input. */
  public void save(ProjectionWrite projection, List<ProjectionReplacement> replacements)
      throws SQLException {
    requireOpen();
    Objects.requireNonNull(projection, "projection");
    replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));

    boolean previousAutoCommit = writeConnection.getAutoCommit();
    writeConnection.setAutoCommit(false);
    try {
      Map<String, ObjectWrite> objectsById = indexObjects(projection.objects());
      Map<String, Optional<ResolvedObject>> storedObjects = new LinkedHashMap<>();
      validateExistingClassifications(objectsById, storedObjects);
      List<CanonicalChange> canonicalChanges = findCanonicalChanges(objectsById, storedObjects);
      validateProjection(projection, objectsById, storedObjects);

      markDependentEntriesStale(canonicalChanges);
      markReplacedProfilesStale(replacements);
      upsertObjects(projection.objects());
      upsertRelations(projection.relations());
      upsertIndexStates(projection.indexStates());
      writeConnection.commit();
    } catch (SQLException | RuntimeException error) {
      writeConnection.rollback();
      throw error;
    } finally {
      writeConnection.setAutoCommit(previousAutoCommit);
    }
  }

  private void markReplacedProfilesStale(List<ProjectionReplacement> replacements)
      throws SQLException {
    try (PreparedStatement statement =
        writeConnection.prepareStatement(
            """
            UPDATE knowledge_object
            SET lifecycle_state = ?
            WHERE knowledge_class = ?
              AND canonical_source_id = ?
              AND generator_id = ?
              AND generator_version <> ?
              AND lifecycle_state = ?
            """)) {
      for (ProjectionReplacement replacement : replacements) {
        statement.setString(1, STALE);
        statement.setString(2, KnowledgeClass.DERIVED.name());
        statement.setString(3, replacement.canonicalSourceId());
        statement.setString(4, replacement.generatorId());
        statement.setString(5, replacement.incomingGeneratorVersion());
        statement.setString(6, CURRENT);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  /** Records a recoverable projection failure after canonical data has already committed. */
  public void recordProjectionFailure(IndexStateWrite failure) throws SQLException {
    Objects.requireNonNull(failure, "failure");
    if (failure.status() != IndexStateStatus.FAILED) {
      throw new IllegalArgumentException("A recoverable failure must use FAILED status");
    }
    save(new ProjectionWrite(List.of(), List.of(), List.of(failure)));
  }

  /** Reads one stored object by its stable identifier. */
  public Optional<StoredObject> findObject(String objectId) throws SQLException {
    requireText(objectId, "objectId");
    return inReadTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT id, object_type, title, content, source_system, source_locator,
                         source_revision, metadata_json, knowledge_class, content_hash,
                         canonical_source_id, generator_id, generator_version, input_object_id,
                         input_content_hash, lifecycle_state
                  FROM knowledge_object
                  WHERE id = ?
                  """)) {
            statement.setString(1, objectId);
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? Optional.of(readStoredObject(result)) : Optional.empty();
            }
          }
        });
  }

  /** Reads a deterministic bounded object snapshot for retrieval or projection rebuilds. */
  public List<StoredObject> findObjects(int limit, boolean includeStale) throws SQLException {
    requirePositive(limit, "limit");
    return inReadTransaction(
        connection -> {
          String sql =
              """
              SELECT id, object_type, title, content, source_system, source_locator,
                     source_revision, metadata_json, knowledge_class, content_hash,
                     canonical_source_id, generator_id, generator_version, input_object_id,
                     input_content_hash, lifecycle_state
              FROM knowledge_object
              WHERE (? = 1 OR lifecycle_state = ?)
              ORDER BY id
              LIMIT ?
              """;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, includeStale ? 1 : 0);
            statement.setString(2, CURRENT);
            statement.setInt(3, limit);
            List<StoredObject> objects = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                objects.add(readStoredObject(result));
              }
            }
            return List.copyOf(objects);
          }
        });
  }

  /** Reads the next deterministic object page after a stable ID for bounded index rebuilds. */
  public List<StoredObject> findObjectsAfter(
      Optional<String> afterObjectId, int limit, boolean includeStale) throws SQLException {
    Objects.requireNonNull(afterObjectId, "afterObjectId");
    afterObjectId.ifPresent(value -> requireText(value, "afterObjectId"));
    requirePositive(limit, "limit");
    return inReadTransaction(
        connection -> {
          String sql =
              """
              SELECT id, object_type, title, content, source_system, source_locator,
                     source_revision, metadata_json, knowledge_class, content_hash,
                     canonical_source_id, generator_id, generator_version, input_object_id,
                     input_content_hash, lifecycle_state
              FROM knowledge_object
              WHERE id > ? AND (? = 1 OR lifecycle_state = ?)
              ORDER BY id
              LIMIT ?
              """;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, afterObjectId.orElse(""));
            statement.setInt(2, includeStale ? 1 : 0);
            statement.setString(3, CURRENT);
            statement.setInt(4, limit);
            List<StoredObject> objects = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                objects.add(readStoredObject(result));
              }
            }
            return List.copyOf(objects);
          }
        });
  }

  /** Reads bounded, evidence-complete relations that touch one object. */
  public List<StoredRelation> findRelationsForObject(String objectId, int limit) throws SQLException {
    requireText(objectId, "objectId");
    requirePositive(limit, "limit");
    return inReadTransaction(connection -> readRelationsForObject(connection, objectId, limit));
  }

  /** Reads bounded rebuildable projection states for one object. */
  public List<StoredIndexState> findIndexStatesForObject(String objectId, int limit)
      throws SQLException {
    requireText(objectId, "objectId");
    requirePositive(limit, "limit");
    return inReadTransaction(connection -> readIndexStatesForObject(connection, objectId, limit));
  }

  /** Creates one virtual project and persists its stable identifier and initial lifecycle state. */
  public VirtualProject createVirtualProject(String name, String description) throws SQLException {
    requireText(name, "name");
    Objects.requireNonNull(description, "description");
    String projectId = stableId("virtual-project:v1:", List.of(UUID.randomUUID().toString()));
    String now = Instant.now().toString();
    VirtualProject project =
        new VirtualProject(
            projectId,
            name,
            description,
            VirtualProjectIndexStatus.NOT_BUILT,
            VirtualProjectSyncStatus.NEVER_RUN,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            now,
            now);
    inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement(
                  """
                  INSERT INTO virtual_project(
                    id, name, description, index_status, sync_status,
                    last_sync_started_at, last_sync_completed_at, last_sync_diagnostic,
                    created_at, updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """)) {
            statement.setString(1, project.id());
            statement.setString(2, project.name());
            statement.setString(3, project.description());
            statement.setString(4, project.indexStatus().name());
            statement.setString(5, project.syncStatus().name());
            setOptionalText(statement, 6, project.lastSyncStartedAt());
            setOptionalText(statement, 7, project.lastSyncCompletedAt());
            setOptionalText(statement, 8, project.lastSyncDiagnostic());
            statement.setString(9, project.createdAt());
            statement.setString(10, project.updatedAt());
            statement.executeUpdate();
          }
          return null;
        });
    return project;
  }

  /** Reads one virtual project by its stable identifier. */
  public Optional<VirtualProject> findVirtualProject(String projectId) throws SQLException {
    requireText(projectId, "projectId");
    return inReadTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT id, name, description, index_status, sync_status,
                         last_sync_started_at, last_sync_completed_at, last_sync_diagnostic,
                         created_at, updated_at
                  FROM virtual_project
                  WHERE id = ?
                  """)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? Optional.of(readVirtualProject(result)) : Optional.empty();
            }
          }
        });
  }

  /** Reads a deterministic bounded project snapshot. */
  public List<VirtualProject> findVirtualProjects(int limit) throws SQLException {
    requirePositive(limit, "limit");
    return inReadTransaction(
        connection -> {
          List<VirtualProject> projects = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT id, name, description, index_status, sync_status,
                         last_sync_started_at, last_sync_completed_at, last_sync_diagnostic,
                         created_at, updated_at
                  FROM virtual_project
                  ORDER BY id
                  LIMIT ?
                  """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                projects.add(readVirtualProject(result));
              }
            }
          }
          return List.copyOf(projects);
        });
  }

  /** Updates project metadata without changing its index or synchronization state. */
  public VirtualProject updateVirtualProject(String projectId, String name, String description)
      throws SQLException {
    requireText(projectId, "projectId");
    requireText(name, "name");
    Objects.requireNonNull(description, "description");
    String now = Instant.now().toString();
    inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement(
                  "UPDATE virtual_project SET name = ?, description = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, now);
            statement.setString(4, projectId);
            if (statement.executeUpdate() == 0) {
              throw new IllegalArgumentException("Unknown virtual project: " + projectId);
            }
          }
          return null;
        });
    return findVirtualProject(projectId).orElseThrow();
  }

  /** Removes project metadata and its references, leaving all referenced source files untouched. */
  public boolean deleteVirtualProject(String projectId) throws SQLException {
    requireText(projectId, "projectId");
    return inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement("DELETE FROM virtual_project WHERE id = ?")) {
            statement.setString(1, projectId);
            return statement.executeUpdate() > 0;
          }
        });
  }

  /** Adds or reuses one project resource reference using a deterministic reference identifier. */
  public VirtualProjectResourceReference addVirtualProjectResource(
      String projectId, VirtualProjectResourceKind kind, String sourcePath) throws SQLException {
    return addVirtualProjectResource(projectId, kind, sourcePath, Optional.empty());
  }

  /** Adds one resource reference with optional automatically detected Git provenance. */
  public VirtualProjectResourceReference addVirtualProjectResource(
      String projectId,
      VirtualProjectResourceKind kind,
      String sourcePath,
      Optional<VirtualProjectGitProvenance> gitProvenance)
      throws SQLException {
    requireText(projectId, "projectId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(gitProvenance, "gitProvenance");
    String normalizedPath = normalizeResourcePath(sourcePath);
    if (findVirtualProject(projectId).isEmpty()) {
      throw new IllegalArgumentException("Unknown virtual project: " + projectId);
    }
    String referenceId =
        stableId("virtual-project-resource:v1:", List.of(projectId, kind.name(), normalizedPath));
    String now = Instant.now().toString();
    inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement(
                  """
                  INSERT INTO virtual_project_resource(
                    id, project_id, resource_kind, source_path,
                    git_repository_root, git_relative_path, created_at, updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT(project_id, resource_kind, source_path) DO UPDATE SET
                    git_repository_root = excluded.git_repository_root,
                    git_relative_path = excluded.git_relative_path,
                    updated_at = excluded.updated_at
                  """)) {
            statement.setString(1, referenceId);
            statement.setString(2, projectId);
            statement.setString(3, kind.name());
            statement.setString(4, normalizedPath);
            setOptionalText(
                statement, 5, gitProvenance.map(VirtualProjectGitProvenance::repositoryRoot));
            setOptionalText(
                statement, 6, gitProvenance.map(VirtualProjectGitProvenance::relativePath));
            statement.setString(7, now);
            statement.setString(8, now);
            statement.executeUpdate();
          }
          updateProjectUpdatedAt(projectId, now);
          return null;
        });
    return findVirtualProjectResource(projectId, referenceId).orElseThrow();
  }

  /** Reads bounded resource references for one project in stable path order. */
  public List<VirtualProjectResourceReference> findVirtualProjectResources(
      String projectId, int limit) throws SQLException {
    requireText(projectId, "projectId");
    requirePositive(limit, "limit");
    return inReadTransaction(
        connection -> {
          List<VirtualProjectResourceReference> references = new ArrayList<>();
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT id, project_id, resource_kind, source_path,
                         git_repository_root, git_relative_path, created_at, updated_at
                  FROM virtual_project_resource
                  WHERE project_id = ?
                  ORDER BY source_path, id
                  LIMIT ?
                  """)) {
            statement.setString(1, projectId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                references.add(readVirtualProjectResource(result));
              }
            }
          }
          return List.copyOf(references);
        });
  }

  /** Removes one reference only when it belongs to the selected project. */
  public boolean deleteVirtualProjectResource(String projectId, String referenceId)
      throws SQLException {
    requireText(projectId, "projectId");
    requireText(referenceId, "referenceId");
    return inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement(
                  "DELETE FROM virtual_project_resource WHERE project_id = ? AND id = ?")) {
            statement.setString(1, projectId);
            statement.setString(2, referenceId);
            boolean removed = statement.executeUpdate() > 0;
            if (removed) {
              updateProjectUpdatedAt(projectId, Instant.now().toString());
            }
            return removed;
          }
        });
  }

  /** Updates persisted index and synchronization state for one project. */
  public VirtualProject updateVirtualProjectState(
      String projectId,
      VirtualProjectIndexStatus indexStatus,
      VirtualProjectSyncStatus syncStatus,
      Optional<String> lastSyncStartedAt,
      Optional<String> lastSyncCompletedAt,
      Optional<String> lastSyncDiagnostic)
      throws SQLException {
    requireText(projectId, "projectId");
    Objects.requireNonNull(indexStatus, "indexStatus");
    Objects.requireNonNull(syncStatus, "syncStatus");
    validateOptionalText(lastSyncStartedAt, "lastSyncStartedAt");
    validateOptionalText(lastSyncCompletedAt, "lastSyncCompletedAt");
    validateOptionalText(lastSyncDiagnostic, "lastSyncDiagnostic");
    String now = Instant.now().toString();
    inWriteTransaction(
        () -> {
          try (PreparedStatement statement =
              writeConnection.prepareStatement(
                  """
                  UPDATE virtual_project
                  SET index_status = ?, sync_status = ?, last_sync_started_at = ?,
                      last_sync_completed_at = ?, last_sync_diagnostic = ?, updated_at = ?
                  WHERE id = ?
                  """)) {
            statement.setString(1, indexStatus.name());
            statement.setString(2, syncStatus.name());
            setOptionalText(statement, 3, lastSyncStartedAt);
            setOptionalText(statement, 4, lastSyncCompletedAt);
            setOptionalText(statement, 5, lastSyncDiagnostic);
            statement.setString(6, now);
            statement.setString(7, projectId);
            if (statement.executeUpdate() == 0) {
              throw new IllegalArgumentException("Unknown virtual project: " + projectId);
            }
          }
          return null;
        });
    return findVirtualProject(projectId).orElseThrow();
  }

  private Optional<VirtualProjectResourceReference> findVirtualProjectResource(
      String projectId, String referenceId) throws SQLException {
    return inReadTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
              SELECT id, project_id, resource_kind, source_path,
                     git_repository_root, git_relative_path, created_at, updated_at
                  FROM virtual_project_resource
                  WHERE project_id = ? AND id = ?
                  """)) {
            statement.setString(1, projectId);
            statement.setString(2, referenceId);
            try (ResultSet result = statement.executeQuery()) {
              return result.next()
                  ? Optional.of(readVirtualProjectResource(result))
                  : Optional.empty();
            }
          }
        });
  }

  private void updateProjectUpdatedAt(String projectId, String updatedAt) throws SQLException {
    try (PreparedStatement statement =
        writeConnection.prepareStatement("UPDATE virtual_project SET updated_at = ? WHERE id = ?")) {
      statement.setString(1, updatedAt);
      statement.setString(2, projectId);
      statement.executeUpdate();
    }
  }

  @Override
  public void close() throws SQLException {
    if (!closed) {
      closed = true;
      writeConnection.close();
    }
  }

  private void migrate() throws SQLException {
    boolean previousAutoCommit = writeConnection.getAutoCommit();
    writeConnection.setAutoCommit(false);
    try (Statement statement = writeConnection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS schema_migrations (
            version TEXT PRIMARY KEY,
            applied_at TEXT NOT NULL
          )
          """);
      if (!migrationApplied(MIGRATION_VERSION)) {
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS knowledge_object (
            id TEXT PRIMARY KEY,
            object_type TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT,
            source_system TEXT NOT NULL,
            source_locator TEXT NOT NULL,
            source_revision TEXT,
            metadata_json TEXT NOT NULL,
            knowledge_class TEXT NOT NULL CHECK (knowledge_class IN ('CANONICAL', 'DERIVED')),
            content_hash TEXT NOT NULL,
            canonical_source_id TEXT,
            generator_id TEXT,
            generator_version TEXT,
            input_object_id TEXT,
            input_content_hash TEXT,
            lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('CURRENT', 'STALE')),
            CHECK (
              (knowledge_class = 'CANONICAL'
                AND canonical_source_id IS NULL
                AND generator_id IS NULL
                AND generator_version IS NULL
                AND input_object_id IS NULL
                AND input_content_hash IS NULL)
              OR
              (knowledge_class = 'DERIVED'
                AND canonical_source_id IS NOT NULL
                AND generator_id IS NOT NULL
                AND generator_version IS NOT NULL
                AND input_object_id IS NOT NULL
                AND input_content_hash IS NOT NULL)
            ),
            FOREIGN KEY(canonical_source_id) REFERENCES knowledge_object(id),
            FOREIGN KEY(input_object_id) REFERENCES knowledge_object(id)
          )
            """);
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS knowledge_relation (
            relation_id TEXT PRIMARY KEY,
            source_object_id TEXT NOT NULL,
            relation_type TEXT NOT NULL,
            target_object_id TEXT NOT NULL,
            UNIQUE(source_object_id, relation_type, target_object_id),
            FOREIGN KEY(source_object_id) REFERENCES knowledge_object(id),
            FOREIGN KEY(target_object_id) REFERENCES knowledge_object(id)
          )
            """);
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS knowledge_evidence (
            evidence_id TEXT PRIMARY KEY,
            relation_id TEXT NOT NULL,
            evidence_type TEXT NOT NULL,
            source_system TEXT NOT NULL,
            source_locator TEXT NOT NULL,
            source_revision TEXT,
            location TEXT NOT NULL,
            confidence REAL NOT NULL CHECK (confidence >= 0.0 AND confidence <= 1.0),
            FOREIGN KEY(relation_id) REFERENCES knowledge_relation(relation_id)
          )
            """);
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS knowledge_index_state (
            projection_name TEXT NOT NULL,
            projection_version TEXT NOT NULL,
            object_id TEXT NOT NULL,
            input_object_id TEXT NOT NULL,
            input_content_hash TEXT NOT NULL,
            state TEXT NOT NULL CHECK (state IN ('CURRENT', 'STALE', 'FAILED')),
            last_success_at TEXT,
            failure_detail TEXT,
            PRIMARY KEY(projection_name, projection_version, object_id),
            FOREIGN KEY(object_id) REFERENCES knowledge_object(id),
            FOREIGN KEY(input_object_id) REFERENCES knowledge_object(id)
          )
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_object_input
            ON knowledge_object(input_object_id, input_content_hash)
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_relation_source
            ON knowledge_relation(source_object_id)
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_relation_target
            ON knowledge_relation(target_object_id)
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_evidence_relation
            ON knowledge_evidence(relation_id)
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_index_state_input
            ON knowledge_index_state(input_object_id, input_content_hash)
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_knowledge_index_state_object
            ON knowledge_index_state(object_id)
            """);
        insertMigrationMarker(MIGRATION_VERSION);
      }
      if (!migrationApplied(VIRTUAL_PROJECT_MIGRATION_VERSION)) {
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS virtual_project (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              description TEXT NOT NULL,
              index_status TEXT NOT NULL CHECK (index_status IN ('NOT_BUILT', 'CURRENT', 'STALE', 'FAILED')),
              sync_status TEXT NOT NULL CHECK (sync_status IN ('NEVER_RUN', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED')),
              last_sync_started_at TEXT,
              last_sync_completed_at TEXT,
              last_sync_diagnostic TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """);
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS virtual_project_resource (
              id TEXT PRIMARY KEY,
              project_id TEXT NOT NULL,
              resource_kind TEXT NOT NULL CHECK (resource_kind IN ('FILE', 'DIRECTORY')),
              source_path TEXT NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              UNIQUE(project_id, resource_kind, source_path),
              FOREIGN KEY(project_id) REFERENCES virtual_project(id) ON DELETE CASCADE
            )
            """);
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_virtual_project_resource_project
            ON virtual_project_resource(project_id, source_path)
            """);
        insertMigrationMarker(VIRTUAL_PROJECT_MIGRATION_VERSION);
      }
      if (!migrationApplied(VIRTUAL_PROJECT_GIT_PROVENANCE_MIGRATION_VERSION)) {
        statement.execute(
            "ALTER TABLE virtual_project_resource ADD COLUMN git_repository_root TEXT");
        statement.execute(
            "ALTER TABLE virtual_project_resource ADD COLUMN git_relative_path TEXT");
        insertMigrationMarker(VIRTUAL_PROJECT_GIT_PROVENANCE_MIGRATION_VERSION);
      }
      writeConnection.commit();
    } catch (SQLException error) {
      writeConnection.rollback();
      throw error;
    } finally {
      writeConnection.setAutoCommit(previousAutoCommit);
    }
  }

  private void insertMigrationMarker(String version) throws SQLException {
    try (PreparedStatement marker =
        writeConnection.prepareStatement(
            "INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)")) {
      marker.setString(1, version);
      marker.setString(2, Instant.now().toString());
      marker.executeUpdate();
    }
  }

  private boolean migrationApplied(String version) throws SQLException {
    try (PreparedStatement statement =
        writeConnection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
      statement.setString(1, version);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private Map<String, ObjectWrite> indexObjects(List<ObjectWrite> objectWrites) {
    Map<String, ObjectWrite> indexed = new LinkedHashMap<>();
    for (ObjectWrite objectWrite : objectWrites) {
      ObjectWrite existing = indexed.putIfAbsent(objectWrite.object().id(), objectWrite);
      if (existing != null && !existing.equals(objectWrite)) {
        throw new IllegalArgumentException(
            "A projection cannot contain conflicting writes for object " + objectWrite.object().id());
      }
    }
    return indexed;
  }

  private void validateExistingClassifications(
      Map<String, ObjectWrite> objectsById, Map<String, Optional<ResolvedObject>> storedObjects)
      throws SQLException {
    for (ObjectWrite objectWrite : objectsById.values()) {
      Optional<ResolvedObject> stored = findResolvedStoredObject(objectWrite.object().id(), storedObjects);
      if (stored.isPresent() && stored.get().knowledgeClass() != objectWrite.object().knowledgeClass()) {
        throw new IllegalArgumentException(
            "Knowledge object "
                + objectWrite.object().id()
                + " cannot change canonical/derived classification");
      }
    }
  }

  private List<CanonicalChange> findCanonicalChanges(
      Map<String, ObjectWrite> objectsById, Map<String, Optional<ResolvedObject>> storedObjects)
      throws SQLException {
    List<CanonicalChange> changes = new ArrayList<>();
    for (ObjectWrite objectWrite : objectsById.values()) {
      KnowledgeObject object = objectWrite.object();
      if (object.knowledgeClass() != KnowledgeClass.CANONICAL) {
        continue;
      }
      Optional<ResolvedObject> stored = findResolvedStoredObject(object.id(), storedObjects);
      if (stored.isEmpty()) {
        continue;
      }
      ResolvedObject current = stored.get();
      if (!current.contentHash().equals(object.contentHash())) {
        changes.add(CanonicalChange.contentHashChanged(object.id(), current.contentHash()));
      } else if (!current.sourceRevision().equals(object.source().revision())) {
        changes.add(CanonicalChange.sourceRevisionChanged(object.id()));
      }
    }
    return changes;
  }

  private void validateProjection(
      ProjectionWrite projection,
      Map<String, ObjectWrite> objectsById,
      Map<String, Optional<ResolvedObject>> storedObjects)
      throws SQLException {
    for (ObjectWrite objectWrite : projection.objects()) {
      validateObjectWrite(objectWrite, objectsById, storedObjects);
    }
    for (RelationProjection relation : projection.relations()) {
      if (relation.evidence().isEmpty()) {
        throw new IllegalArgumentException("A relation requires at least one evidence item");
      }
      resolveObject(relation.sourceObjectId(), objectsById, storedObjects, "relation source");
      resolveObject(relation.targetObjectId(), objectsById, storedObjects, "relation target");
    }
    for (IndexStateWrite state : projection.indexStates()) {
      validateIndexState(state, objectsById, storedObjects);
    }
  }

  private void validateObjectWrite(
      ObjectWrite objectWrite,
      Map<String, ObjectWrite> objectsById,
      Map<String, Optional<ResolvedObject>> storedObjects)
      throws SQLException {
    KnowledgeObject object = objectWrite.object();
    if (object.knowledgeClass() == KnowledgeClass.CANONICAL) {
      if (objectWrite.inputObjectId().isPresent()) {
        throw new IllegalArgumentException("Canonical knowledge cannot declare an input object");
      }
      return;
    }

    String canonicalSourceId =
        object
            .canonicalSourceId()
            .orElseThrow(() -> new IllegalArgumentException("Derived knowledge lacks canonical source"));
    String generatorId =
        object.generatorId().orElseThrow(() -> new IllegalArgumentException("Derived knowledge lacks generator"));
    String generatorVersion =
        object
            .generatorVersion()
            .orElseThrow(() -> new IllegalArgumentException("Derived knowledge lacks generator version"));
    String inputContentHash =
        object
            .inputContentHash()
            .orElseThrow(() -> new IllegalArgumentException("Derived knowledge lacks input hash"));
    requireText(generatorId, "generatorId");
    requireText(generatorVersion, "generatorVersion");

    ResolvedObject canonicalSource =
        resolveObject(canonicalSourceId, objectsById, storedObjects, "derived canonical source");
    if (canonicalSource.knowledgeClass() != KnowledgeClass.CANONICAL) {
      throw new IllegalArgumentException("Derived knowledge must reference a canonical source");
    }
    String inputObjectId =
        objectWrite
            .inputObjectId()
            .orElseThrow(() -> new IllegalArgumentException("Derived knowledge lacks input object"));
    ResolvedObject inputObject =
        resolveObject(inputObjectId, objectsById, storedObjects, "derived input object");
    if (inputObject.knowledgeClass() != KnowledgeClass.CANONICAL) {
      throw new IllegalArgumentException("Derived input must reference a canonical knowledge object");
    }
    if (!inputContentHash.equals(inputObject.contentHash())) {
      throw new IllegalArgumentException("Derived input hash does not match its canonical input object");
    }
  }

  private void validateIndexState(
      IndexStateWrite state,
      Map<String, ObjectWrite> objectsById,
      Map<String, Optional<ResolvedObject>> storedObjects)
      throws SQLException {
    resolveObject(state.objectId(), objectsById, storedObjects, "index-state object");
    ResolvedObject inputObject =
        resolveObject(state.inputObjectId(), objectsById, storedObjects, "index-state input object");
    if (inputObject.knowledgeClass() != KnowledgeClass.CANONICAL) {
      throw new IllegalArgumentException("Index state input must reference a canonical knowledge object");
    }
    if (!state.inputContentHash().equals(inputObject.contentHash())) {
      throw new IllegalArgumentException("Index state input hash does not match its canonical input object");
    }
    if (state.status() == IndexStateStatus.FAILED && state.failureDetail().isEmpty()) {
      throw new IllegalArgumentException("FAILED index state requires recoverable failure detail");
    }
    if (state.status() != IndexStateStatus.FAILED && state.failureDetail().isPresent()) {
      throw new IllegalArgumentException("Only FAILED index state can retain failure detail");
    }
  }

  private ResolvedObject resolveObject(
      String objectId,
      Map<String, ObjectWrite> objectsById,
      Map<String, Optional<ResolvedObject>> storedObjects,
      String role)
      throws SQLException {
    ObjectWrite incoming = objectsById.get(objectId);
    if (incoming != null) {
      KnowledgeObject object = incoming.object();
      return new ResolvedObject(object.knowledgeClass(), object.contentHash(), object.source().revision());
    }
    return findResolvedStoredObject(objectId, storedObjects)
        .orElseThrow(() -> new IllegalArgumentException("Unknown " + role + " object " + objectId));
  }

  private Optional<ResolvedObject> findResolvedStoredObject(
      String objectId, Map<String, Optional<ResolvedObject>> storedObjects) throws SQLException {
    Optional<ResolvedObject> known = storedObjects.get(objectId);
    if (known != null) {
      return known;
    }
    Optional<ResolvedObject> resolved;
    try (PreparedStatement statement =
        writeConnection.prepareStatement(
            """
            SELECT knowledge_class, content_hash, source_revision
            FROM knowledge_object
            WHERE id = ?
            """)) {
      statement.setString(1, objectId);
      try (ResultSet result = statement.executeQuery()) {
        resolved =
            result.next()
                ? Optional.of(
                    new ResolvedObject(
                        KnowledgeClass.valueOf(result.getString("knowledge_class")),
                        result.getString("content_hash"),
                        Optional.ofNullable(result.getString("source_revision"))))
                : Optional.empty();
      }
    }
    storedObjects.put(objectId, resolved);
    return resolved;
  }

  private void markDependentEntriesStale(List<CanonicalChange> canonicalChanges) throws SQLException {
    for (CanonicalChange change : canonicalChanges) {
      if (change.staleAllInputs()) {
        markAllInputDependentsStale(change.objectId());
      } else {
        markHashDependentsStale(change.objectId(), change.previousInputHash());
      }
    }
  }

  private void markAllInputDependentsStale(String inputObjectId) throws SQLException {
    try (PreparedStatement objects =
            writeConnection.prepareStatement(
                """
                UPDATE knowledge_object
                SET lifecycle_state = ?
                WHERE knowledge_class = ? AND input_object_id = ? AND lifecycle_state = ?
                """);
        PreparedStatement states =
            writeConnection.prepareStatement(
                """
                UPDATE knowledge_index_state
                SET state = ?, failure_detail = NULL
                WHERE input_object_id = ? AND state = ?
                """)) {
      objects.setString(1, STALE);
      objects.setString(2, KnowledgeClass.DERIVED.name());
      objects.setString(3, inputObjectId);
      objects.setString(4, CURRENT);
      objects.executeUpdate();

      states.setString(1, STALE);
      states.setString(2, inputObjectId);
      states.setString(3, CURRENT);
      states.executeUpdate();
    }
  }

  private void markHashDependentsStale(String inputObjectId, String previousInputHash)
      throws SQLException {
    try (PreparedStatement objects =
            writeConnection.prepareStatement(
                """
                UPDATE knowledge_object
                SET lifecycle_state = ?
                WHERE knowledge_class = ?
                  AND input_object_id = ?
                  AND input_content_hash = ?
                  AND lifecycle_state = ?
                """);
        PreparedStatement states =
            writeConnection.prepareStatement(
                """
                UPDATE knowledge_index_state
                SET state = ?, failure_detail = NULL
                WHERE input_object_id = ? AND input_content_hash = ? AND state = ?
                """)) {
      objects.setString(1, STALE);
      objects.setString(2, KnowledgeClass.DERIVED.name());
      objects.setString(3, inputObjectId);
      objects.setString(4, previousInputHash);
      objects.setString(5, CURRENT);
      objects.executeUpdate();

      states.setString(1, STALE);
      states.setString(2, inputObjectId);
      states.setString(3, previousInputHash);
      states.setString(4, CURRENT);
      states.executeUpdate();
    }
  }

  private void upsertObjects(List<ObjectWrite> objectWrites) throws SQLException {
    for (KnowledgeClass knowledgeClass : List.of(KnowledgeClass.CANONICAL, KnowledgeClass.DERIVED)) {
      for (ObjectWrite objectWrite : objectWrites) {
        if (objectWrite.object().knowledgeClass() == knowledgeClass) {
          upsertObject(objectWrite);
        }
      }
    }
  }

  private void upsertObject(ObjectWrite objectWrite) throws SQLException {
    KnowledgeObject object = objectWrite.object();
    try (PreparedStatement statement =
        writeConnection.prepareStatement(
            """
            INSERT INTO knowledge_object(
              id, object_type, title, content, source_system, source_locator, source_revision,
              metadata_json, knowledge_class, content_hash, canonical_source_id, generator_id,
              generator_version, input_object_id, input_content_hash, lifecycle_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              object_type = excluded.object_type,
              title = excluded.title,
              content = excluded.content,
              source_system = excluded.source_system,
              source_locator = excluded.source_locator,
              source_revision = excluded.source_revision,
              metadata_json = excluded.metadata_json,
              knowledge_class = excluded.knowledge_class,
              content_hash = excluded.content_hash,
              canonical_source_id = excluded.canonical_source_id,
              generator_id = excluded.generator_id,
              generator_version = excluded.generator_version,
              input_object_id = excluded.input_object_id,
              input_content_hash = excluded.input_content_hash,
              lifecycle_state = excluded.lifecycle_state
            """)) {
      statement.setString(1, object.id());
      statement.setString(2, object.type().name());
      statement.setString(3, object.title());
      setOptionalText(statement, 4, object.content());
      statement.setString(5, object.source().system());
      statement.setString(6, object.source().locator());
      setOptionalText(statement, 7, object.source().revision());
      statement.setString(8, encodeMetadata(object.metadata()));
      statement.setString(9, object.knowledgeClass().name());
      statement.setString(10, object.contentHash());
      setOptionalText(statement, 11, object.canonicalSourceId());
      setOptionalText(statement, 12, object.generatorId());
      setOptionalText(statement, 13, object.generatorVersion());
      setOptionalText(statement, 14, objectWrite.inputObjectId());
      setOptionalText(statement, 15, object.inputContentHash());
      statement.setString(16, CURRENT);
      statement.executeUpdate();
    }
  }

  private void upsertRelations(List<RelationProjection> relations) throws SQLException {
    for (RelationProjection relation : relations) {
      String relationId = relationId(relation);
      try (PreparedStatement statement =
          writeConnection.prepareStatement(
              """
              INSERT INTO knowledge_relation(relation_id, source_object_id, relation_type, target_object_id)
              VALUES (?, ?, ?, ?)
              ON CONFLICT(relation_id) DO NOTHING
              """)) {
        statement.setString(1, relationId);
        statement.setString(2, relation.sourceObjectId());
        statement.setString(3, relation.type().name());
        statement.setString(4, relation.targetObjectId());
        statement.executeUpdate();
      }
      for (Evidence evidence : relation.evidence()) {
        upsertEvidence(relationId, evidence);
      }
    }
  }

  private void upsertEvidence(String relationId, Evidence evidence) throws SQLException {
    try (PreparedStatement statement =
        writeConnection.prepareStatement(
            """
            INSERT INTO knowledge_evidence(
              evidence_id, relation_id, evidence_type, source_system, source_locator,
              source_revision, location, confidence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(evidence_id) DO NOTHING
            """)) {
      statement.setString(1, evidenceId(relationId, evidence));
      statement.setString(2, relationId);
      statement.setString(3, evidence.type().name());
      statement.setString(4, evidence.source().system());
      statement.setString(5, evidence.source().locator());
      setOptionalText(statement, 6, evidence.source().revision());
      statement.setString(7, evidence.location());
      statement.setDouble(8, evidence.confidence());
      statement.executeUpdate();
    }
  }

  private void upsertIndexStates(List<IndexStateWrite> states) throws SQLException {
    for (IndexStateWrite state : states) {
      try (PreparedStatement statement =
          writeConnection.prepareStatement(
              """
              INSERT INTO knowledge_index_state(
                projection_name, projection_version, object_id, input_object_id, input_content_hash,
                state, last_success_at, failure_detail)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT(projection_name, projection_version, object_id) DO UPDATE SET
                input_object_id = excluded.input_object_id,
                input_content_hash = excluded.input_content_hash,
                state = excluded.state,
                last_success_at = CASE
                  WHEN excluded.state = 'CURRENT' THEN excluded.last_success_at
                  ELSE knowledge_index_state.last_success_at
                END,
                failure_detail = excluded.failure_detail
              """)) {
        statement.setString(1, state.projectionName());
        statement.setString(2, state.projectionVersion());
        statement.setString(3, state.objectId());
        statement.setString(4, state.inputObjectId());
        statement.setString(5, state.inputContentHash());
        statement.setString(6, state.status().name());
        statement.setString(
            7, state.status() == IndexStateStatus.CURRENT ? Instant.now().toString() : null);
        setOptionalText(statement, 8, state.failureDetail());
        statement.executeUpdate();
      }
    }
  }

  private List<StoredRelation> readRelationsForObject(Connection connection, String objectId, int limit)
      throws SQLException {
    List<StoredRelation> relations = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT relation_id, source_object_id, relation_type, target_object_id
            FROM knowledge_relation
            WHERE source_object_id = ? OR target_object_id = ?
            ORDER BY relation_id
            LIMIT ?
            """)) {
      statement.setString(1, objectId);
      statement.setString(2, objectId);
      statement.setInt(3, limit);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String relationId = result.getString("relation_id");
          relations.add(
              new StoredRelation(
                  relationId,
                  result.getString("source_object_id"),
                  RelationType.valueOf(result.getString("relation_type")),
                  result.getString("target_object_id"),
                  readEvidence(connection, relationId)));
        }
      }
    }
    return List.copyOf(relations);
  }

  private List<Evidence> readEvidence(Connection connection, String relationId) throws SQLException {
    List<Evidence> evidence = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT evidence_type, source_system, source_locator, source_revision, location, confidence
            FROM knowledge_evidence
            WHERE relation_id = ?
            ORDER BY evidence_id
            """)) {
      statement.setString(1, relationId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          evidence.add(
              new Evidence(
                  EvidenceType.valueOf(result.getString("evidence_type")),
                  new SourceRef(
                      result.getString("source_system"),
                      result.getString("source_locator"),
                      Optional.ofNullable(result.getString("source_revision"))),
                  result.getString("location"),
                  result.getDouble("confidence")));
        }
      }
    }
    return List.copyOf(evidence);
  }

  private List<StoredIndexState> readIndexStatesForObject(
      Connection connection, String objectId, int limit) throws SQLException {
    List<StoredIndexState> states = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT projection_name, projection_version, object_id, input_object_id, input_content_hash,
                   state, last_success_at, failure_detail
            FROM knowledge_index_state
            WHERE object_id = ?
            ORDER BY projection_name, projection_version
            LIMIT ?
            """)) {
      statement.setString(1, objectId);
      statement.setInt(2, limit);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          states.add(
              new StoredIndexState(
                  result.getString("projection_name"),
                  result.getString("projection_version"),
                  result.getString("object_id"),
                  result.getString("input_object_id"),
                  result.getString("input_content_hash"),
                  IndexStateStatus.valueOf(result.getString("state")),
                  Optional.ofNullable(result.getString("last_success_at")),
                  Optional.ofNullable(result.getString("failure_detail"))));
        }
      }
    }
    return List.copyOf(states);
  }

  private VirtualProject readVirtualProject(ResultSet result) throws SQLException {
    return new VirtualProject(
        result.getString("id"),
        result.getString("name"),
        result.getString("description"),
        VirtualProjectIndexStatus.valueOf(result.getString("index_status")),
        VirtualProjectSyncStatus.valueOf(result.getString("sync_status")),
        Optional.ofNullable(result.getString("last_sync_started_at")),
        Optional.ofNullable(result.getString("last_sync_completed_at")),
        Optional.ofNullable(result.getString("last_sync_diagnostic")),
        result.getString("created_at"),
        result.getString("updated_at"));
  }

  private VirtualProjectResourceReference readVirtualProjectResource(ResultSet result)
      throws SQLException {
    String gitRepositoryRoot = result.getString("git_repository_root");
    String gitRelativePath = result.getString("git_relative_path");
    Optional<VirtualProjectGitProvenance> gitProvenance =
        gitRepositoryRoot == null || gitRelativePath == null
            ? Optional.empty()
            : Optional.of(new VirtualProjectGitProvenance(gitRepositoryRoot, gitRelativePath));
    return new VirtualProjectResourceReference(
        result.getString("id"),
        result.getString("project_id"),
        VirtualProjectResourceKind.valueOf(result.getString("resource_kind")),
        result.getString("source_path"),
        gitProvenance,
        result.getString("created_at"),
        result.getString("updated_at"));
  }

  private StoredObject readStoredObject(ResultSet result) throws SQLException {
    KnowledgeObject object =
        new KnowledgeObject(
            result.getString("id"),
            ObjectType.valueOf(result.getString("object_type")),
            result.getString("title"),
            Optional.ofNullable(result.getString("content")),
            new SourceRef(
                result.getString("source_system"),
                result.getString("source_locator"),
                Optional.ofNullable(result.getString("source_revision"))),
            decodeMetadata(result.getString("metadata_json")),
            KnowledgeClass.valueOf(result.getString("knowledge_class")),
            result.getString("content_hash"),
            Optional.ofNullable(result.getString("canonical_source_id")),
            Optional.ofNullable(result.getString("generator_id")),
            Optional.ofNullable(result.getString("generator_version")),
            Optional.ofNullable(result.getString("input_content_hash")));
    return new StoredObject(
        object,
        Optional.ofNullable(result.getString("input_object_id")),
        ObjectLifecycleState.valueOf(result.getString("lifecycle_state")));
  }

  private <T> T inReadTransaction(ReadOperation<T> operation) throws SQLException {
    requireOpen();
    try (Connection readConnection = DriverManager.getConnection(jdbcUrl)) {
      enableForeignKeys(readConnection);
      readConnection.createStatement().execute("PRAGMA query_only = ON");
      readConnection.setAutoCommit(false);
      try {
        T result = operation.execute(readConnection);
        readConnection.commit();
        return result;
      } catch (SQLException | RuntimeException error) {
        readConnection.rollback();
        throw error;
      }
    }
  }

  private <T> T inWriteTransaction(WriteOperation<T> operation) throws SQLException {
    requireOpen();
    boolean previousAutoCommit = writeConnection.getAutoCommit();
    writeConnection.setAutoCommit(false);
    try {
      T result = operation.execute();
      writeConnection.commit();
      return result;
    } catch (SQLException | RuntimeException error) {
      writeConnection.rollback();
      throw error;
    } finally {
      writeConnection.setAutoCommit(previousAutoCommit);
    }
  }

  private static void enableForeignKeys(Connection connection) throws SQLException {
    connection.createStatement().execute("PRAGMA foreign_keys = ON");
  }

  private static void setOptionalText(PreparedStatement statement, int index, Optional<String> value)
      throws SQLException {
    statement.setString(index, value.orElse(null));
  }

  private static String relationId(RelationProjection relation) {
    return stableId(
        "relation:v1:",
        List.of(relation.sourceObjectId(), relation.type().name(), relation.targetObjectId()));
  }

  private static String evidenceId(String relationId, Evidence evidence) {
    Optional<String> revision = evidence.source().revision();
    return stableId(
        "evidence:v1:",
        List.of(
            relationId,
            evidence.type().name(),
            evidence.source().system(),
            evidence.source().locator(),
            revision.isPresent() ? "present" : "absent",
            revision.orElse(""),
            evidence.location(),
            Long.toUnsignedString(Double.doubleToLongBits(evidence.confidence()))));
  }

  private static String stableId(String prefix, List<String> fields) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JRE is missing SHA-256", error);
    }
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
    return prefix + HexFormat.of().formatHex(digest.digest());
  }

  private static String encodeMetadata(Map<String, String> metadata) {
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : new TreeMap<>(metadata).entrySet()) {
      if (!first) {
        json.append(',');
      }
      appendJsonString(json, entry.getKey());
      json.append(':');
      appendJsonString(json, entry.getValue());
      first = false;
    }
    return json.append('}').toString();
  }

  private static void appendJsonString(StringBuilder target, String value) {
    target.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> target.append("\\\"");
        case '\\' -> target.append("\\\\");
        case '\b' -> target.append("\\b");
        case '\f' -> target.append("\\f");
        case '\n' -> target.append("\\n");
        case '\r' -> target.append("\\r");
        case '\t' -> target.append("\\t");
        default -> {
          if (character < 0x20) {
            target.append("\\u%04x".formatted((int) character));
          } else {
            target.append(character);
          }
        }
      }
    }
    target.append('"');
  }

  private static Map<String, String> decodeMetadata(String json) {
    return new MetadataJsonReader(json).readObject();
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("SqliteKnowledgeStore is closed");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void validateOptionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(text -> requireText(text, name));
  }

  private static String normalizeResourcePath(String sourcePath) {
    requireText(sourcePath, "sourcePath");
    try {
      return Path.of(sourcePath).toAbsolutePath().normalize().toString();
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("sourcePath is invalid", error);
    }
  }

  @FunctionalInterface
  private interface ReadOperation<T> {
    T execute(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface WriteOperation<T> {
    T execute() throws SQLException;
  }

  private record ResolvedObject(
      KnowledgeClass knowledgeClass, String contentHash, Optional<String> sourceRevision) {}

  private record CanonicalChange(
      String objectId, boolean staleAllInputs, String previousInputHash) {
    static CanonicalChange contentHashChanged(String objectId, String previousInputHash) {
      return new CanonicalChange(objectId, false, previousInputHash);
    }

    static CanonicalChange sourceRevisionChanged(String objectId) {
      return new CanonicalChange(objectId, true, "");
    }
  }

  /** A candidate collection that is validated and committed as one SQLite write transaction. */
  public record ProjectionWrite(
      List<ObjectWrite> objects,
      List<RelationProjection> relations,
      List<IndexStateWrite> indexStates) {
    public ProjectionWrite {
      objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
      relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
      indexStates = List.copyOf(Objects.requireNonNull(indexStates, "indexStates"));
    }
  }

  /** Persisted metadata and lifecycle state for one virtual project. */
  public record VirtualProject(
      String id,
      String name,
      String description,
      VirtualProjectIndexStatus indexStatus,
      VirtualProjectSyncStatus syncStatus,
      Optional<String> lastSyncStartedAt,
      Optional<String> lastSyncCompletedAt,
      Optional<String> lastSyncDiagnostic,
      String createdAt,
      String updatedAt) {
    public VirtualProject {
      requireText(id, "id");
      requireText(name, "name");
      Objects.requireNonNull(description, "description");
      Objects.requireNonNull(indexStatus, "indexStatus");
      Objects.requireNonNull(syncStatus, "syncStatus");
      validateOptionalText(lastSyncStartedAt, "lastSyncStartedAt");
      validateOptionalText(lastSyncCompletedAt, "lastSyncCompletedAt");
      validateOptionalText(lastSyncDiagnostic, "lastSyncDiagnostic");
      requireText(createdAt, "createdAt");
      requireText(updatedAt, "updatedAt");
    }
  }

  /** Persisted file or directory membership in a virtual project. */
  public record VirtualProjectResourceReference(
      String id,
      String projectId,
      VirtualProjectResourceKind kind,
      String sourcePath,
      Optional<VirtualProjectGitProvenance> gitProvenance,
      String createdAt,
      String updatedAt) {
    public VirtualProjectResourceReference(
        String id,
        String projectId,
        VirtualProjectResourceKind kind,
        String sourcePath,
        String createdAt,
        String updatedAt) {
      this(id, projectId, kind, sourcePath, Optional.empty(), createdAt, updatedAt);
    }

    public VirtualProjectResourceReference {
      requireText(id, "id");
      requireText(projectId, "projectId");
      Objects.requireNonNull(kind, "kind");
      requireText(sourcePath, "sourcePath");
      Objects.requireNonNull(gitProvenance, "gitProvenance");
      requireText(createdAt, "createdAt");
      requireText(updatedAt, "updatedAt");
    }
  }

  /** Git worktree identity detected for a project resource, when the path is in a worktree. */
  public record VirtualProjectGitProvenance(String repositoryRoot, String relativePath) {
    public VirtualProjectGitProvenance {
      requireText(repositoryRoot, "repositoryRoot");
      requireText(relativePath, "relativePath");
      if (relativePath.startsWith("/") || relativePath.contains("\\")) {
        throw new IllegalArgumentException(
            "relativePath must use normalized repository-relative syntax");
      }
    }
  }

  public enum VirtualProjectResourceKind {
    FILE,
    DIRECTORY
  }

  public enum VirtualProjectIndexStatus {
    NOT_BUILT,
    CURRENT,
    STALE,
    FAILED
  }

  public enum VirtualProjectSyncStatus {
    NEVER_RUN,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    SKIPPED
  }

  /** No-schema profile replacement instruction executed in the projection save transaction. */
  public record ProjectionReplacement(
      String canonicalSourceId, String generatorId, String incomingGeneratorVersion) {
    public ProjectionReplacement {
      requireText(canonicalSourceId, "canonicalSourceId");
      requireText(generatorId, "generatorId");
      requireText(incomingGeneratorVersion, "incomingGeneratorVersion");
    }
  }

  /** A knowledge-object write with the canonical object whose hash was consumed as input. */
  public record ObjectWrite(KnowledgeObject object, Optional<String> inputObjectId) {
    public ObjectWrite {
      Objects.requireNonNull(object, "object");
      Objects.requireNonNull(inputObjectId, "inputObjectId");
      inputObjectId.ifPresent(value -> requireText(value, "inputObjectId"));
    }

    public static ObjectWrite canonical(KnowledgeObject object) {
      return new ObjectWrite(object, Optional.empty());
    }

    public static ObjectWrite derived(KnowledgeObject object, String inputObjectId) {
      return new ObjectWrite(object, Optional.of(inputObjectId));
    }
  }

  /** One logical edge and every evidence item supplied for that edge in this projection. */
  public record RelationProjection(
      String sourceObjectId, RelationType type, String targetObjectId, List<Evidence> evidence) {
    public RelationProjection {
      requireText(sourceObjectId, "sourceObjectId");
      Objects.requireNonNull(type, "type");
      requireText(targetObjectId, "targetObjectId");
      evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    public static RelationProjection from(Relation relation) {
      Objects.requireNonNull(relation, "relation");
      return new RelationProjection(
          relation.sourceObjectId(), relation.type(), relation.targetObjectId(), List.of(relation.evidence()));
    }
  }

  /** A rebuildable projection state and the canonical input hash it consumed. */
  public record IndexStateWrite(
      String projectionName,
      String projectionVersion,
      String objectId,
      String inputObjectId,
      String inputContentHash,
      IndexStateStatus status,
      Optional<String> failureDetail) {
    public IndexStateWrite {
      requireText(projectionName, "projectionName");
      requireText(projectionVersion, "projectionVersion");
      requireText(objectId, "objectId");
      requireText(inputObjectId, "inputObjectId");
      requireText(inputContentHash, "inputContentHash");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(failureDetail, "failureDetail");
      failureDetail.ifPresent(value -> requireText(value, "failureDetail"));
    }

    public static IndexStateWrite current(
        String projectionName,
        String projectionVersion,
        String objectId,
        String inputObjectId,
        String inputContentHash) {
      return new IndexStateWrite(
          projectionName,
          projectionVersion,
          objectId,
          inputObjectId,
          inputContentHash,
          IndexStateStatus.CURRENT,
          Optional.empty());
    }

    public static IndexStateWrite failed(
        String projectionName,
        String projectionVersion,
        String objectId,
        String inputObjectId,
        String inputContentHash,
        String failureDetail) {
      return new IndexStateWrite(
          projectionName,
          projectionVersion,
          objectId,
          inputObjectId,
          inputContentHash,
          IndexStateStatus.FAILED,
          Optional.of(failureDetail));
    }
  }

  /** Status of a rebuildable index projection. */
  public enum IndexStateStatus {
    CURRENT,
    STALE,
    FAILED
  }

  /** Lifecycle state persisted for a knowledge object. */
  public enum ObjectLifecycleState {
    CURRENT,
    STALE
  }

  /** A stored knowledge object plus Store-only input and lifecycle data. */
  public record StoredObject(
      KnowledgeObject object, Optional<String> inputObjectId, ObjectLifecycleState lifecycleState) {
    public StoredObject {
      Objects.requireNonNull(object, "object");
      Objects.requireNonNull(inputObjectId, "inputObjectId");
      Objects.requireNonNull(lifecycleState, "lifecycleState");
    }
  }

  /** A stored logical relation with its complete bounded evidence set. */
  public record StoredRelation(
      String relationId,
      String sourceObjectId,
      RelationType type,
      String targetObjectId,
      List<Evidence> evidence) {
    public StoredRelation {
      requireText(relationId, "relationId");
      requireText(sourceObjectId, "sourceObjectId");
      Objects.requireNonNull(type, "type");
      requireText(targetObjectId, "targetObjectId");
      evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
  }

  /** A stored rebuildable projection state. */
  public record StoredIndexState(
      String projectionName,
      String projectionVersion,
      String objectId,
      String inputObjectId,
      String inputContentHash,
      IndexStateStatus status,
      Optional<String> lastSuccessAt,
      Optional<String> failureDetail) {
    public StoredIndexState {
      requireText(projectionName, "projectionName");
      requireText(projectionVersion, "projectionVersion");
      requireText(objectId, "objectId");
      requireText(inputObjectId, "inputObjectId");
      requireText(inputContentHash, "inputContentHash");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(lastSuccessAt, "lastSuccessAt");
      Objects.requireNonNull(failureDetail, "failureDetail");
    }
  }

  private static final class MetadataJsonReader {
    private final String source;
    private int cursor;

    private MetadataJsonReader(String source) {
      this.source = Objects.requireNonNull(source, "metadata JSON");
    }

    private Map<String, String> readObject() {
      Map<String, String> metadata = new LinkedHashMap<>();
      expect('{');
      skipWhitespace();
      if (consume('}')) {
        ensureEnd();
        return Map.of();
      }
      while (true) {
        String key = readString();
        expect(':');
        String value = readString();
        if (metadata.putIfAbsent(key, value) != null) {
          throw invalid("metadata JSON cannot contain duplicate keys");
        }
        skipWhitespace();
        if (consume('}')) {
          ensureEnd();
          return Map.copyOf(metadata);
        }
        expect(',');
      }
    }

    private String readString() {
      skipWhitespace();
      if (!consume('"')) {
        throw invalid("expected JSON string");
      }
      StringBuilder value = new StringBuilder();
      while (cursor < source.length()) {
        char character = source.charAt(cursor++);
        if (character == '"') {
          return value.toString();
        }
        if (character < 0x20) {
          throw invalid("unescaped control character in JSON string");
        }
        if (character != '\\') {
          value.append(character);
          continue;
        }
        if (cursor >= source.length()) {
          throw invalid("truncated JSON escape");
        }
        char escape = source.charAt(cursor++);
        switch (escape) {
          case '"' -> value.append('"');
          case '\\' -> value.append('\\');
          case '/' -> value.append('/');
          case 'b' -> value.append('\b');
          case 'f' -> value.append('\f');
          case 'n' -> value.append('\n');
          case 'r' -> value.append('\r');
          case 't' -> value.append('\t');
          case 'u' -> value.append(readUnicodeEscape());
          default -> throw invalid("unsupported JSON escape");
        }
      }
      throw invalid("unterminated JSON string");
    }

    private char readUnicodeEscape() {
      if (cursor + 4 > source.length()) {
        throw invalid("truncated unicode escape");
      }
      int value = 0;
      for (int index = 0; index < 4; index++) {
        int digit = Character.digit(source.charAt(cursor++), 16);
        if (digit < 0) {
          throw invalid("invalid unicode escape");
        }
        value = (value << 4) | digit;
      }
      return (char) value;
    }

    private void expect(char expected) {
      skipWhitespace();
      if (!consume(expected)) {
        throw invalid("expected '" + expected + "'");
      }
    }

    private boolean consume(char expected) {
      if (cursor < source.length() && source.charAt(cursor) == expected) {
        cursor++;
        return true;
      }
      return false;
    }

    private void skipWhitespace() {
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
        cursor++;
      }
    }

    private void ensureEnd() {
      skipWhitespace();
      if (cursor != source.length()) {
        throw invalid("unexpected trailing metadata JSON content");
      }
    }

    private IllegalArgumentException invalid(String message) {
      return new IllegalArgumentException(message);
    }
  }
}
