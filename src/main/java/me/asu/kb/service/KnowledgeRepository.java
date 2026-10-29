package me.asu.kb.service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class KnowledgeRepository implements Closeable {

  private final Connection connection;
  private final String jdbcUrl;
  private final ThreadLocal<Connection> activeReadConnection = new ThreadLocal<>();
  private PersonalCatalog personalCatalog;

  /** Called before transport admission, never from an HTTP handler. */
  PersonalCatalog initializePersonalCatalog(Path backupFile) throws SQLException {
    if (personalCatalog == null) personalCatalog = new PersonalCatalog(connection, backupFile);
    return personalCatalog;
  }

  @FunctionalInterface
  interface Transaction<T> {
    T execute() throws Exception;
  }

  @FunctionalInterface
  interface ReadTransaction<T> {
    T execute(Connection connection) throws Exception;
  }

  KnowledgeRepository(Path dbFile) throws SQLException {
    this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    this.connection = DriverManager.getConnection(jdbcUrl);
    this.connection.createStatement().execute("PRAGMA foreign_keys = ON");
    initSchema();
  }

  long upsertDocument(String rootPath, String path, String fileType, String charset, String hash)
      throws SQLException {
    Optional<Long> existing = findDocumentIdByPath(path);
    if (existing.isPresent()) {
      long documentId = existing.get();
      deleteChunks(documentId);
      try (PreparedStatement ps =
          connection.prepareStatement(
              "UPDATE documents SET root_path = ?, file_type = ?, charset = ?, hash = ?, indexed_at = ? WHERE id = ?")) {
        ps.setString(1, rootPath);
        ps.setString(2, fileType);
        ps.setString(3, charset);
        ps.setString(4, hash);
        ps.setString(5, Instant.now().toString());
        ps.setLong(6, documentId);
        ps.executeUpdate();
      }
      ensureArticle(documentId, rootPath, path, fileType, hash);
      return documentId;
    }

    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO documents(root_path, path, file_type, charset, hash, indexed_at)
            VALUES(?, ?, ?, ?, ?, ?)
            """,
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, rootPath);
      ps.setString(2, path);
      ps.setString(3, fileType);
      ps.setString(4, charset);
      ps.setString(5, hash);
      ps.setString(6, Instant.now().toString());
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (rs.next()) {
          long documentId = rs.getLong(1);
          ensureArticle(documentId, rootPath, path, fileType, hash);
          return documentId;
        }
      }
    }
    throw new SQLException("Failed to insert document");
  }

  <T> T inTransaction(Transaction<T> transaction) throws Exception {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      T result = transaction.execute();
      connection.commit();
      return result;
    } catch (Exception error) {
      connection.rollback();
      throw error;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  /**
   * Runs a compound read on a short-lived, isolated JDBC connection.  The
   * connection is deliberately not shared with the FIFO write connection, so
   * a reader cannot observe statements inside an uncommitted write unit.
   */
  <T> T inReadTransaction(ReadTransaction<T> transaction) throws Exception {
    Connection active = activeReadConnection.get();
    if (active != null) {
      return transaction.execute(active);
    }
    try (Connection readConnection = DriverManager.getConnection(jdbcUrl)) {
      readConnection.createStatement().execute("PRAGMA foreign_keys = ON");
      // Xerial SQLite does not support Connection#setReadOnly after connect.
      // query_only applies the same no-write boundary to this short-lived reader.
      readConnection.createStatement().execute("PRAGMA query_only = ON");
      readConnection.setAutoCommit(false);
      try {
        activeReadConnection.set(readConnection);
        T result = transaction.execute(readConnection);
        readConnection.commit();
        return result;
      } catch (Exception error) {
        readConnection.rollback();
        throw error;
      } finally {
        activeReadConnection.remove();
      }
    }
  }

  private Connection readConnection() {
    Connection active = activeReadConnection.get();
    return active == null ? connection : active;
  }

  private void ensureArticle(long documentId, String rootPath, String path, String fileType,
      String hash) throws SQLException {
    try (PreparedStatement existing = connection.prepareStatement(
        "SELECT 1 FROM articles WHERE document_id = ?")) {
      existing.setLong(1, documentId);
      try (ResultSet rs = existing.executeQuery()) {
        if (rs.next()) {
          return;
        }
      }
    }
    ArticleSourceKind sourceKind = sourceKind(rootPath, path);
    String now = Instant.now().toString();
    try (PreparedStatement article = connection.prepareStatement(
        "INSERT INTO articles(article_id, document_id, current_revision_number, state, "
            + "projection_state, projection_revision_number, created_at, updated_at) "
            + "VALUES(?, ?, 1, 'ACTIVE', 'PENDING', 1, ?, ?)")) {
      article.setLong(1, documentId);
      article.setLong(2, documentId);
      article.setString(3, now);
      article.setString(4, now);
      article.executeUpdate();
    }
    try (PreparedStatement revision = connection.prepareStatement(
        "INSERT INTO article_revisions(article_id, revision_number, content_hash, format, "
            + "source_path, source_kind, created_at) VALUES(?, 1, ?, ?, ?, ?, ?)")) {
      revision.setLong(1, documentId);
      revision.setString(2, hash);
      revision.setString(3, fileType);
      revision.setString(4, path);
      revision.setString(5, sourceKind.name());
      revision.setString(6, now);
      revision.executeUpdate();
    }
  }

  private ArticleSourceKind sourceKind(String rootPath, String path) {
    String root = rootPath == null ? "" : rootPath.replace('\\', '/').toLowerCase();
    String candidate = path == null ? "" : path.replace('\\', '/').toLowerCase();
    if (root.endsWith("/articles") || candidate.contains("/articles/")) {
      return ArticleSourceKind.ARTICLES;
    }
    if (root.endsWith("/uploads") || candidate.contains("/uploads/")) {
      return ArticleSourceKind.UPLOADS;
    }
    return ArticleSourceKind.ROOT;
  }

  public void upsertDocumentSummary(long documentId, String summary, String summaryModel)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            UPDATE documents
            SET summary = ?, summary_model = ?, summary_updated_at = ?
            WHERE id = ?
            """)) {
      ps.setString(1, summary);
      ps.setString(2, summaryModel);
      ps.setString(3, Instant.now().toString());
      ps.setLong(4, documentId);
      ps.executeUpdate();
    }
  }

  public void putCheckpoint(String name, String value) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO workspace_checkpoints(name, value) VALUES(?, ?) "
            + "ON CONFLICT(name) DO UPDATE SET value = excluded.value")) {
      ps.setString(1, name);
      ps.setString(2, value);
      ps.executeUpdate();
    }
  }

  public Optional<String> checkpoint(String name) throws SQLException {
    try (PreparedStatement ps = readConnection().prepareStatement(
        "SELECT value FROM workspace_checkpoints WHERE name = ?")) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
      }
    }
  }

  long createProjectionBatch(String businessCheckpoint, List<String> paths) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO projection_batches(business_checkpoint, projected_at, created_at) VALUES(?, NULL, ?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, businessCheckpoint);
      ps.setString(2, Instant.now().toString());
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (!rs.next()) {
          throw new SQLException("failed to create projection batch");
        }
        long batchId = rs.getLong(1);
        try (PreparedStatement pathStatement = connection.prepareStatement(
            "INSERT INTO projection_batch_paths(batch_id, path) VALUES(?, ?)")) {
          for (String path : paths) {
            pathStatement.setLong(1, batchId);
            pathStatement.setString(2, path);
            pathStatement.addBatch();
          }
          pathStatement.executeBatch();
        }
        return batchId;
      }
    }
  }

  List<ProjectionBatchRow> findPendingProjectionBatches() throws SQLException {
    Map<Long, ProjectionBatchRowBuilder> batches = new LinkedHashMap<>();
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT b.id, b.business_checkpoint, b.created_at, p.path "
            + "FROM projection_batches b JOIN projection_batch_paths p ON p.batch_id = b.id "
            + "WHERE b.projected_at IS NULL ORDER BY b.id, p.path");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        long id = rs.getLong("id");
        ProjectionBatchRowBuilder batch = batches.computeIfAbsent(
            id,
            ignored -> new ProjectionBatchRowBuilder(
                id, rsString(rs, "business_checkpoint"), rsString(rs, "created_at")));
        batch.paths.add(rs.getString("path"));
      }
    }
    return batches.values().stream().map(ProjectionBatchRowBuilder::build).toList();
  }

  void markProjectionBatchProjected(long batchId, String projectionCheckpoint) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "UPDATE projection_batches SET projected_at = ?, projection_checkpoint = ? WHERE id = ?")) {
      ps.setString(1, Instant.now().toString());
      ps.setString(2, projectionCheckpoint);
      ps.setLong(3, batchId);
      ps.executeUpdate();
    }
  }

  void markAllPendingProjectionBatchesProjected(String projectionCheckpoint) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "UPDATE projection_batches SET projected_at = ?, projection_checkpoint = ? WHERE projected_at IS NULL")) {
      ps.setString(1, Instant.now().toString());
      ps.setString(2, projectionCheckpoint);
      ps.executeUpdate();
    }
  }

  int pendingProjectionBatchCount() throws SQLException {
    try (Statement statement = readConnection().createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM projection_batches WHERE projected_at IS NULL")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  List<ChunkRow> findChunksByDocumentId(long documentId) throws SQLException {
    List<ChunkRow> rows = new ArrayList<>();
    try (PreparedStatement ps = readConnection().prepareStatement(
        "SELECT c.id, c.document_id, c.chunk_no, c.title, c.content, c.start_line, c.end_line, d.path, d.root_path "
            + "FROM chunks c JOIN documents d ON d.id = c.document_id WHERE c.document_id = ? ORDER BY c.chunk_no")) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(readChunkRow(rs));
        }
      }
    }
    return rows;
  }

  void activateRoot(String rootPath) throws SQLException {
    setRootLifecycle(rootPath, "active", null);
  }

  void setRootLifecycle(String rootPath, String lifecycle, String reason) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO workspace_roots(path, lifecycle, reason, updated_at) VALUES(?, ?, ?, ?) "
            + "ON CONFLICT(path) DO UPDATE SET lifecycle = excluded.lifecycle, "
            + "reason = excluded.reason, updated_at = excluded.updated_at")) {
      ps.setString(1, rootPath);
      ps.setString(2, lifecycle);
      ps.setString(3, reason);
      ps.setString(4, Instant.now().toString());
      ps.executeUpdate();
    }
  }

  boolean isDocumentInActiveRoot(long documentId) throws SQLException {
    try (PreparedStatement ps = readConnection().prepareStatement(
        "SELECT r.lifecycle FROM documents d LEFT JOIN workspace_roots r ON r.path = d.root_path WHERE d.id = ?")) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && (rs.getString(1) == null || "active".equals(rs.getString(1)));
      }
    }
  }

  List<RootRow> findRoots() throws SQLException {
    List<RootRow> roots = new ArrayList<>();
    try (Statement st = readConnection().createStatement();
        ResultSet rs = st.executeQuery("SELECT path, lifecycle, reason, updated_at FROM workspace_roots ORDER BY path")) {
      while (rs.next()) {
        roots.add(new RootRow(rs.getString("path"), rs.getString("lifecycle"),
            Optional.ofNullable(rs.getString("reason")), rs.getString("updated_at")));
      }
    }
    return roots;
  }

  public void upsertDocumentTag(long documentId, String tag, String source) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO document_tags(document_id, tag, source, created_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(document_id, tag, source) DO UPDATE SET
              created_at = excluded.created_at
            """)) {
      ps.setLong(1, documentId);
      ps.setString(2, tag);
      ps.setString(3, source);
      ps.setString(4, Instant.now().toString());
      ps.executeUpdate();
    }
  }

  public List<DocumentTagRow> findTagsByDocumentId(long documentId) throws SQLException {
    List<DocumentTagRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT document_id, tag, source, created_at
            FROM document_tags
            WHERE document_id = ?
            ORDER BY source, tag
            """)) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new DocumentTagRow(
                  rs.getLong("document_id"),
                  rs.getString("tag"),
                  rs.getString("source"),
                  rs.getString("created_at")));
        }
      }
    }
    return rows;
  }

  public void deleteTagsByDocumentId(long documentId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("DELETE FROM document_tags WHERE document_id = ?")) {
      ps.setLong(1, documentId);
      ps.executeUpdate();
    }
  }

  public void deleteTagsBySource(long documentId, String source) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("DELETE FROM document_tags WHERE document_id = ? AND source = ?")) {
      ps.setLong(1, documentId);
      ps.setString(2, source);
      ps.executeUpdate();
    }
  }

  public void upsertDocumentMetadata(
      long documentId, String field, String value, String source, String generatorModel)
      throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO document_metadata_values(
                document_id, field, value, source, generator_model, created_at, updated_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(document_id, field, source) DO UPDATE SET
              value = excluded.value,
              generator_model = excluded.generator_model,
              updated_at = excluded.updated_at
            """)) {
      ps.setLong(1, documentId);
      ps.setString(2, field);
      ps.setString(3, value);
      ps.setString(4, source);
      ps.setString(5, generatorModel);
      ps.setString(6, now);
      ps.setString(7, now);
      ps.executeUpdate();
    }
  }

  public List<DocumentMetadataRow> findDocumentMetadata(long documentId) throws SQLException {
    List<DocumentMetadataRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT document_id, field, value, source, generator_model, created_at, updated_at
            FROM document_metadata_values
            WHERE document_id = ?
            ORDER BY CASE source WHEN 'manual' THEN 0 WHEN 'source' THEN 1 WHEN 'ai' THEN 2 ELSE 3 END,
              field
            """)) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new DocumentMetadataRow(
                  rs.getLong("document_id"),
                  rs.getString("field"),
                  rs.getString("value"),
                  rs.getString("source"),
                  Optional.ofNullable(rs.getString("generator_model")),
                  rs.getString("created_at"),
                  rs.getString("updated_at")));
        }
      }
    }
    return rows;
  }

  public EffectiveDocumentMetadata findEffectiveDocumentMetadata(long documentId)
      throws SQLException {
    DocumentRow document =
        findDocumentById(documentId).orElseThrow(() -> new SQLException("Document not found: " + documentId));
    Map<String, EffectiveMetadataValue> valuesByField = new LinkedHashMap<>();
    for (DocumentMetadataRow row : findDocumentMetadata(documentId)) {
      EffectiveMetadataValue candidate =
          new EffectiveMetadataValue(row.value(), row.source(), row.generatorModel());
      EffectiveMetadataValue current = valuesByField.get(row.field());
      if (current == null || sourcePriority(row.source()) > sourcePriority(current.source())) {
        valuesByField.put(row.field(), candidate);
      }
    }

    if (!valuesByField.containsKey("title")) {
      String fallbackTitle = findFirstChunkTitle(documentId).orElseGet(() -> fileName(document.path()));
      if (!fallbackTitle.isBlank()) {
        valuesByField.put("title", new EffectiveMetadataValue(fallbackTitle, "fallback", Optional.empty()));
      }
    }
    if (!valuesByField.containsKey("summary")) {
      document.summary().filter(value -> !value.isBlank())
          .ifPresent(value -> valuesByField.put(
              "summary", new EffectiveMetadataValue(value, "fallback", document.summaryModel().map(Optional::of).orElse(Optional.empty()))));
    }

    Map<String, EffectiveMetadataValue> tagsByValue = new LinkedHashMap<>();
    for (DocumentTagRow row : findTagsByDocumentId(documentId)) {
      EffectiveMetadataValue candidate =
          new EffectiveMetadataValue(row.tag(), effectiveTagSource(row.source()), Optional.empty());
      EffectiveMetadataValue current = tagsByValue.get(row.tag());
      if (current == null || sourcePriority(candidate.source()) > sourcePriority(current.source())) {
        tagsByValue.put(row.tag(), candidate);
      }
    }
    for (DocumentKeywordRow row : findDocumentKeywords(documentId)) {
      // Keywords are independently curated values and use the same source precedence.
      EffectiveMetadataValue candidate =
          new EffectiveMetadataValue(row.keyword(), row.source(), Optional.empty());
      EffectiveMetadataValue current = tagsByValue.get("\u0000keyword:" + row.keyword());
      if (current == null || sourcePriority(row.source()) > sourcePriority(current.source())) {
        tagsByValue.put("\u0000keyword:" + row.keyword(), candidate);
      }
    }

    List<EffectiveMetadataValue> keywords = new ArrayList<>();
    List<EffectiveMetadataValue> tags = new ArrayList<>();
    for (Map.Entry<String, EffectiveMetadataValue> entry : tagsByValue.entrySet()) {
      if (entry.getKey().startsWith("\u0000keyword:")) {
        keywords.add(entry.getValue());
      } else {
        tags.add(entry.getValue());
      }
    }
    return new EffectiveDocumentMetadata(
        documentId,
        Optional.ofNullable(valuesByField.get("title")),
        Optional.ofNullable(valuesByField.get("summary")),
        List.copyOf(tags),
        List.copyOf(keywords));
  }

  private Optional<String> findFirstChunkTitle(long documentId) throws SQLException {
    try (PreparedStatement ps = readConnection().prepareStatement(
        "SELECT title FROM chunks WHERE document_id = ? AND title IS NOT NULL "
            + "AND TRIM(title) <> '' ORDER BY chunk_no LIMIT 1")) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  private static String fileName(String path) {
    int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return separator >= 0 && separator + 1 < path.length() ? path.substring(separator + 1) : path;
  }

  private static String effectiveTagSource(String source) {
    return switch (source) {
      case "manual", "source", "ai" -> source;
      default -> "fallback";
    };
  }

  private static int sourcePriority(String source) {
    return switch (source) {
      case "manual" -> 3;
      case "source" -> 2;
      case "ai" -> 1;
      default -> 0;
    };
  }

  public void deleteDocumentMetadataBySource(long documentId, String source) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "DELETE FROM document_metadata_values WHERE document_id = ? AND source = ?")) {
      ps.setLong(1, documentId);
      ps.setString(2, source);
      ps.executeUpdate();
    }
  }

  public void upsertDocumentKeyword(long documentId, String keyword, String source)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO document_keywords(document_id, keyword, source, created_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(document_id, keyword, source) DO UPDATE SET
              created_at = excluded.created_at
            """)) {
      ps.setLong(1, documentId);
      ps.setString(2, keyword);
      ps.setString(3, source);
      ps.setString(4, Instant.now().toString());
      ps.executeUpdate();
    }
  }

  public List<DocumentKeywordRow> findDocumentKeywords(long documentId) throws SQLException {
    List<DocumentKeywordRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT document_id, keyword, source, created_at
            FROM document_keywords
            WHERE document_id = ?
            ORDER BY source, keyword
            """)) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new DocumentKeywordRow(
                  rs.getLong("document_id"),
                  rs.getString("keyword"),
                  rs.getString("source"),
                  rs.getString("created_at")));
        }
      }
    }
    return rows;
  }

  public void deleteDocumentKeywordsBySource(long documentId, String source) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "DELETE FROM document_keywords WHERE document_id = ? AND source = ?")) {
      ps.setLong(1, documentId);
      ps.setString(2, source);
      ps.executeUpdate();
    }
  }

  public void insertUploadIdentity(
      String contentHash,
      long documentId,
      String storagePath,
      String originalFilename,
      String relativePath,
      String batchId,
      String receivedAt)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO document_uploads(
                content_hash, document_id, storage_path, original_filename,
                relative_path, batch_id, received_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """)) {
      ps.setString(1, contentHash);
      ps.setLong(2, documentId);
      ps.setString(3, storagePath);
      ps.setString(4, originalFilename);
      ps.setString(5, relativePath);
      ps.setString(6, batchId);
      ps.setString(7, receivedAt);
      ps.executeUpdate();
    }
  }

  public Optional<DocumentUploadRow> findUploadByContentHash(String contentHash)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT content_hash, document_id, storage_path, original_filename,
                   relative_path, batch_id, received_at
            FROM document_uploads
            WHERE content_hash = ?
            """)) {
      ps.setString(1, contentHash);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new DocumentUploadRow(
                rs.getString("content_hash"),
                rs.getLong("document_id"),
                rs.getString("storage_path"),
                rs.getString("original_filename"),
                rs.getString("relative_path"),
                rs.getString("batch_id"),
                rs.getString("received_at")));
      }
    }
  }

  long insertChunk(long documentId, int chunkNo, String title, String content, int startLine, int endLine)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO chunks(document_id, chunk_no, title, content, start_line, end_line)
            VALUES(?, ?, ?, ?, ?, ?)
            """,
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, documentId);
      ps.setInt(2, chunkNo);
      ps.setString(3, title);
      ps.setString(4, content);
      ps.setInt(5, startLine);
      ps.setInt(6, endLine);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    throw new SQLException("Failed to insert chunk");
  }

  public long insertSession(String title) throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO sessions(title, created_at, updated_at, archived_at, last_message_at)
            VALUES(?, ?, ?, NULL, NULL)
            """,
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, title == null || title.isBlank() ? "Session" : title);
      ps.setString(2, now);
      ps.setString(3, now);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    throw new SQLException("Failed to insert session");
  }

  public Optional<SessionRow> findSessionById(long sessionId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, title, created_at, updated_at, archived_at, last_message_at
            FROM sessions
            WHERE id = ?
            """)) {
      ps.setLong(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readSessionRow(rs));
      }
    }
  }

  public List<SessionRow> findAllSessions() throws SQLException {
    List<SessionRow> rows = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                """
                SELECT id, title, created_at, updated_at, archived_at, last_message_at
                FROM sessions
                ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC, id DESC
                """)) {
      while (rs.next()) {
        rows.add(readSessionRow(rs));
      }
    }
    return rows;
  }

  public void updateSessionTitle(long sessionId, String title) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET title = ?, updated_at = ?
            WHERE id = ?
            """)) {
      ps.setString(1, title == null || title.isBlank() ? "Session" : title);
      ps.setString(2, Instant.now().toString());
      ps.setLong(3, sessionId);
      ps.executeUpdate();
    }
  }

  public void archiveSession(long sessionId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET archived_at = ?, updated_at = ?
            WHERE id = ?
            """)) {
      String now = Instant.now().toString();
      ps.setString(1, now);
      ps.setString(2, now);
      ps.setLong(3, sessionId);
      ps.executeUpdate();
    }
  }

  public void deleteSession(long sessionId) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
      ps.setLong(1, sessionId);
      ps.executeUpdate();
    }
  }

  public void insertSessionMessage(long sessionId, int messageNo, String role, String content)
      throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO session_messages(session_id, message_no, role, content, created_at)
            VALUES(?, ?, ?, ?, ?)
            """)) {
      ps.setLong(1, sessionId);
      ps.setInt(2, messageNo);
      ps.setString(3, role);
      ps.setString(4, content);
      ps.setString(5, now);
      ps.executeUpdate();
    }
    touchSession(sessionId, now);
  }

  public List<SessionMessageRow> findMessagesBySessionId(long sessionId) throws SQLException {
    List<SessionMessageRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, session_id, message_no, role, content, created_at
            FROM session_messages
            WHERE session_id = ?
            ORDER BY message_no, id
            """)) {
      ps.setLong(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(readSessionMessageRow(rs));
        }
      }
    }
    return rows;
  }

  public void upsertSessionSummary(long sessionId, int summaryNo, String summaryText) throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO session_summaries(session_id, summary_no, summary_text, created_at, updated_at)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(session_id, summary_no) DO UPDATE SET
              summary_text = excluded.summary_text,
              updated_at = excluded.updated_at
            """)) {
      ps.setLong(1, sessionId);
      ps.setInt(2, summaryNo);
      ps.setString(3, summaryText);
      ps.setString(4, now);
      ps.setString(5, now);
      ps.executeUpdate();
    }
    touchSession(sessionId, now);
  }

  public List<SessionSummaryRow> findSummariesBySessionId(long sessionId) throws SQLException {
    List<SessionSummaryRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, session_id, summary_no, summary_text, created_at, updated_at
            FROM session_summaries
            WHERE session_id = ?
            ORDER BY summary_no, id
            """)) {
      ps.setLong(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(readSessionSummaryRow(rs));
        }
      }
    }
    return rows;
  }

  Optional<DocumentRow> findDocumentByPath(String path) throws SQLException {
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT id, root_path, path, file_type, charset, hash, summary, summary_model, summary_updated_at
            FROM documents
            WHERE path = ?
            """)) {
      ps.setString(1, path);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readDocumentRow(rs));
      }
    }
  }

  Optional<Long> findDocumentIdByPath(String path) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT id FROM documents WHERE path = ?")) {
      ps.setString(1, path);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
      }
    }
  }

  List<DocumentRow> findDocumentsByRoot(String rootPath) throws SQLException {
    List<DocumentRow> rows = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT id, root_path, path, file_type, charset, hash, summary, summary_model, summary_updated_at
            FROM documents
            WHERE root_path = ?
              AND NOT EXISTS (
                SELECT 1 FROM articles a WHERE a.document_id = documents.id AND a.state = 'DELETED')
            ORDER BY path
            """)) {
      ps.setString(1, rootPath);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(readDocumentRow(rs));
        }
      }
    }
    return rows;
  }

  List<DocumentRow> findAllDocuments() throws SQLException {
    List<DocumentRow> rows = new ArrayList<>();
    try (Statement st = readConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                """
                SELECT id, root_path, path, file_type, charset, hash, summary, summary_model, summary_updated_at
                FROM documents
                WHERE NOT EXISTS (
                  SELECT 1 FROM articles a WHERE a.document_id = documents.id AND a.state = 'DELETED')
                ORDER BY path
                """)) {
      while (rs.next()) {
        rows.add(readDocumentRow(rs));
      }
    }
    return rows;
  }

  DocumentPage findDocumentPage(int limit, int offset, String query) throws SQLException {
    try {
      return inReadTransaction(readConnection -> findDocumentPage(readConnection, limit, offset, query));
    } catch (SQLException error) {
      throw error;
    } catch (Exception error) {
      throw new SQLException("failed to read document page", error);
    }
  }

  private DocumentPage findDocumentPage(Connection readConnection, int limit, int offset, String query)
      throws SQLException {
    String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
    String filter = normalizedQuery.isEmpty() ? null : "%" + escapeLike(normalizedQuery) + "%";
    String where = " WHERE NOT EXISTS (SELECT 1 FROM articles a WHERE a.document_id = documents.id AND a.state = 'DELETED')"
        + (filter == null ? "" : " AND LOWER(path) LIKE ? ESCAPE '\\'");
    int total;
    try (PreparedStatement count = readConnection.prepareStatement("SELECT COUNT(*) FROM documents" + where)) {
      if (filter != null) count.setString(1, filter);
      try (ResultSet rs = count.executeQuery()) {
        total = rs.next() ? rs.getInt(1) : 0;
      }
    }
    List<DocumentRow> rows = new ArrayList<>();
    try (PreparedStatement page = readConnection.prepareStatement(
        "SELECT id, root_path, path, file_type, charset, hash, summary, summary_model, summary_updated_at "
            + "FROM documents" + where + " ORDER BY path, id LIMIT ? OFFSET ?")) {
      int index = 1;
      if (filter != null) page.setString(index++, filter);
      page.setInt(index++, limit);
      page.setInt(index, offset);
      try (ResultSet rs = page.executeQuery()) {
        while (rs.next()) rows.add(readDocumentRow(rs));
      }
    }
    return new DocumentPage(rows, total);
  }


  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  Optional<DocumentRow> findDocumentById(long id) throws SQLException {
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT id, root_path, path, file_type, charset, hash, summary, summary_model, summary_updated_at
            FROM documents
            WHERE id = ?
            """)) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(readDocumentRow(rs));
      }
    }
  }

  List<ChunkRow> findAllChunks() throws SQLException {
    List<ChunkRow> rows = new ArrayList<>();
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                """
                SELECT c.id, c.document_id, c.chunk_no, c.title, c.content, c.start_line, c.end_line,
                       d.path, d.root_path
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                ORDER BY d.path, c.chunk_no
                """)) {
      while (rs.next()) {
        rows.add(
            new ChunkRow(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getInt("chunk_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getString("path"),
                rs.getString("root_path")));
      }
    }
    return rows;
  }

  void upsertChunkEmbedding(
      long chunkId, String embeddingModel, String vectorJson, int vectorDim) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO chunk_embeddings(chunk_id, embedding_model, vector_json, vector_dim, updated_at)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(chunk_id) DO UPDATE SET
              embedding_model = excluded.embedding_model,
              vector_json = excluded.vector_json,
              vector_dim = excluded.vector_dim,
              updated_at = excluded.updated_at
            """)) {
      ps.setLong(1, chunkId);
      ps.setString(2, embeddingModel);
      ps.setString(3, vectorJson);
      ps.setInt(4, vectorDim);
      ps.setString(5, Instant.now().toString());
      ps.executeUpdate();
    }
  }

  Optional<ChunkEmbeddingRow> findChunkEmbeddingByChunkId(long chunkId) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            SELECT chunk_id, embedding_model, vector_json, vector_dim, updated_at
            FROM chunk_embeddings
            WHERE chunk_id = ?
            """)) {
      ps.setLong(1, chunkId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new ChunkEmbeddingRow(
                rs.getLong("chunk_id"),
                rs.getString("embedding_model"),
                rs.getString("vector_json"),
                rs.getInt("vector_dim"),
                rs.getString("updated_at")));
      }
    }
  }

  List<ChunkEmbeddingRow> findChunkEmbeddingsByChunkIds(List<Long> chunkIds) throws SQLException {
    List<ChunkEmbeddingRow> rows = new ArrayList<>();
    if (chunkIds == null || chunkIds.isEmpty()) {
      return rows;
    }

    StringBuilder sql =
        new StringBuilder(
            "SELECT chunk_id, embedding_model, vector_json, vector_dim, updated_at FROM chunk_embeddings WHERE chunk_id IN (");
    for (int i = 0; i < chunkIds.size(); i++) {
      if (i > 0) {
        sql.append(',');
      }
      sql.append('?');
    }
    sql.append(") ORDER BY chunk_id");

    try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
      for (int i = 0; i < chunkIds.size(); i++) {
        ps.setLong(i + 1, chunkIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new ChunkEmbeddingRow(
                  rs.getLong("chunk_id"),
                  rs.getString("embedding_model"),
                  rs.getString("vector_json"),
                  rs.getInt("vector_dim"),
                  rs.getString("updated_at")));
        }
      }
    }
    return rows;
  }

  void deleteDocument(long documentId) throws SQLException {
    try (PreparedStatement deleteChunks =
            connection.prepareStatement("DELETE FROM chunks WHERE document_id = ?");
        PreparedStatement deleteTags =
            connection.prepareStatement("DELETE FROM document_tags WHERE document_id = ?");
        PreparedStatement deleteDoc =
            connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
      deleteChunks.setLong(1, documentId);
      deleteChunks.executeUpdate();
      deleteTags.setLong(1, documentId);
      deleteTags.executeUpdate();
      deleteDoc.setLong(1, documentId);
      deleteDoc.executeUpdate();
    }
  }

  @Override
  public void close() throws IOException {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IOException(e);
    }
  }

  private void initSchema() throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS workspace_checkpoints (
            name TEXT PRIMARY KEY,
            value TEXT NOT NULL
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS projection_batches (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            business_checkpoint TEXT NOT NULL,
            projection_checkpoint TEXT,
            created_at TEXT NOT NULL,
            projected_at TEXT
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS projection_batch_paths (
            batch_id INTEGER NOT NULL,
            path TEXT NOT NULL,
            PRIMARY KEY(batch_id, path),
            FOREIGN KEY(batch_id) REFERENCES projection_batches(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS workspace_roots (
            path TEXT PRIMARY KEY,
            lifecycle TEXT NOT NULL,
            reason TEXT,
            updated_at TEXT NOT NULL
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            root_path TEXT NOT NULL,
            path TEXT NOT NULL UNIQUE,
            file_type TEXT NOT NULL,
            charset TEXT,
            hash TEXT NOT NULL,
            indexed_at TEXT NOT NULL,
            summary TEXT,
            summary_model TEXT,
            summary_updated_at TEXT
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS chunks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            document_id INTEGER NOT NULL,
            chunk_no INTEGER NOT NULL,
            title TEXT,
            content TEXT NOT NULL,
            start_line INTEGER NOT NULL,
            end_line INTEGER NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS document_tags (
            document_id INTEGER NOT NULL,
            tag TEXT NOT NULL,
            source TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(document_id, tag, source),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS document_metadata_values (
            document_id INTEGER NOT NULL,
            field TEXT NOT NULL,
            value TEXT NOT NULL,
            source TEXT NOT NULL,
            generator_model TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY(document_id, field, source),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS document_keywords (
            document_id INTEGER NOT NULL,
            keyword TEXT NOT NULL,
            source TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(document_id, keyword, source),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS document_uploads (
            content_hash TEXT PRIMARY KEY,
            document_id INTEGER NOT NULL,
            storage_path TEXT NOT NULL UNIQUE,
            original_filename TEXT,
            relative_path TEXT,
            batch_id TEXT,
            received_at TEXT NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS chunk_embeddings (
            chunk_id INTEGER PRIMARY KEY,
            embedding_model TEXT NOT NULL,
            vector_json TEXT NOT NULL,
            vector_dim INTEGER NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(chunk_id) REFERENCES chunks(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            archived_at TEXT,
            last_message_at TEXT
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS session_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            message_no INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
            UNIQUE(session_id, message_no)
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS session_summaries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            summary_no INTEGER NOT NULL,
            summary_text TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
            UNIQUE(session_id, summary_no)
          )
          """);
    }
    migrateMetadataSchema();
    migrateArticleRevisionSchema();
  }

  Optional<Article> findArticleById(long articleId) throws SQLException {
    ArticleData data;
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT a.article_id, a.current_revision_number, a.state,
                   a.projection_state, a.projection_revision_number,
                   a.projection_failure_reason, a.updated_at,
                   r.content_hash, r.format, r.source_path, r.source_kind, r.created_at
            FROM articles a
            JOIN article_revisions r
              ON r.article_id = a.article_id
             AND r.revision_number = a.current_revision_number
            WHERE a.article_id = ?
            """)) {
      ps.setLong(1, articleId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        data = readArticleData(rs);
      }
    }
    EffectiveDocumentMetadata metadata = findEffectiveDocumentMetadata(articleId);
    return Optional.of(
        new Article(
            new ArticleId(data.articleId()),
            data.revision(),
            toArticleMetadata(metadata),
            data.state(),
            data.projection()));
  }

  Optional<Article> findArticleByDocumentId(long documentId) throws SQLException {
    try (PreparedStatement ps =
        readConnection().prepareStatement("SELECT article_id FROM articles WHERE document_id = ?")) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? findArticleById(rs.getLong(1)) : Optional.empty();
      }
    }
  }

  RevisionHistory findRevisionHistory(long articleId) throws SQLException {
    List<ArticleRevision> revisions = new ArrayList<>();
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            """
            SELECT revision_number, content_hash, format, source_path, source_kind, created_at
            FROM article_revisions
            WHERE article_id = ?
            ORDER BY revision_number
            """)) {
      ps.setLong(1, articleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          revisions.add(readArticleRevision(rs));
        }
      }
    }
    return new RevisionHistory(new ArticleId(articleId), revisions);
  }

  void insertArticleRevision(ArticleId articleId, ArticleRevision revision) throws SQLException {
    String now = Instant.now().toString();
    try (PreparedStatement revisionStatement =
            connection.prepareStatement(
                """
                INSERT INTO article_revisions(
                    article_id, revision_number, content_hash, format, source_path, source_kind, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """);
        PreparedStatement articleStatement =
            connection.prepareStatement(
                """
                UPDATE articles
                SET current_revision_number = ?, projection_state = 'PENDING',
                    projection_revision_number = ?, projection_failure_reason = NULL, updated_at = ?
                WHERE article_id = ?
                """)) {
      revisionStatement.setLong(1, articleId.value());
      revisionStatement.setLong(2, revision.revisionNumber());
      revisionStatement.setString(3, revision.contentHash());
      revisionStatement.setString(4, revision.format());
      revisionStatement.setString(5, revision.sourcePath());
      revisionStatement.setString(6, revision.sourceKind().name());
      revisionStatement.setString(7, revision.createdAt().toString());
      revisionStatement.executeUpdate();

      articleStatement.setLong(1, revision.revisionNumber());
      articleStatement.setLong(2, revision.revisionNumber());
      articleStatement.setString(3, now);
      articleStatement.setLong(4, articleId.value());
      if (articleStatement.executeUpdate() != 1) {
        throw new SQLException("Article not found: " + articleId.value());
      }
    }
  }

  void updateArticleProjectionStatus(
      ArticleId articleId, ProjectionStatus status) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            UPDATE articles
            SET projection_state = ?, projection_revision_number = ?,
                projection_failure_reason = ?, updated_at = ?
            WHERE article_id = ?
            """)) {
      ps.setString(1, status.state().name());
      ps.setLong(2, status.revisionNumber());
      ps.setString(3, status.failureReason().orElse(null));
      ps.setString(4, status.updatedAt().toString());
      ps.setLong(5, articleId.value());
      if (ps.executeUpdate() != 1) {
        throw new SQLException("Article not found: " + articleId.value());
      }
    }
  }

  void markArticleDeleted(ArticleId articleId, Instant updatedAt) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "UPDATE articles SET state = 'DELETED', updated_at = ? WHERE article_id = ?")) {
      ps.setString(1, updatedAt.toString());
      ps.setLong(2, articleId.value());
      if (ps.executeUpdate() != 1) {
        throw new SQLException("Article not found: " + articleId.value());
      }
    }
  }

  void insertTrashEntry(TrashEntry entry) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO article_trash(article_id, original_path, trash_path, deleted_at, expires_at) "
            + "VALUES(?, ?, ?, ?, ?)")) {
      ps.setLong(1, entry.articleId().value());
      ps.setString(2, entry.originalPath());
      ps.setString(3, entry.trashPath());
      ps.setString(4, entry.deletedAt().toString());
      ps.setString(5, entry.expiresAt().toString());
      ps.executeUpdate();
    }
  }

  List<TrashEntry> findTrashEntries() throws SQLException {
    List<TrashEntry> entries = new ArrayList<>();
    try (Statement st = readConnection().createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT article_id, original_path, trash_path, deleted_at, expires_at "
                + "FROM article_trash ORDER BY deleted_at")) {
      while (rs.next()) {
        entries.add(new TrashEntry(new ArticleId(rs.getLong("article_id")),
            rs.getString("original_path"), rs.getString("trash_path"),
            Instant.parse(rs.getString("deleted_at")), Instant.parse(rs.getString("expires_at"))));
      }
    }
    return entries;
  }

  void deleteArticleRevisionsBefore(long articleId, Instant cutoff, long currentRevision)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "DELETE FROM article_revisions WHERE article_id = ? AND revision_number <> ? AND created_at < ?")) {
      ps.setLong(1, articleId);
      ps.setLong(2, currentRevision);
      ps.setString(3, cutoff.toString());
      ps.executeUpdate();
    }
  }

  void deleteArticleRevision(long articleId, long revisionNumber) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "DELETE FROM article_revisions WHERE article_id = ? AND revision_number = ?")) {
      ps.setLong(1, articleId);
      ps.setLong(2, revisionNumber);
      ps.executeUpdate();
    }
  }

  void deleteDocumentPermanently(long documentId) throws SQLException {
    deleteDocument(documentId);
  }

  void restoreArticle(ArticleId articleId, Instant updatedAt) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "UPDATE articles SET state = 'ACTIVE', projection_state = 'PENDING', "
            + "projection_failure_reason = NULL, updated_at = ? WHERE article_id = ?")) {
      ps.setString(1, updatedAt.toString());
      ps.setLong(2, articleId.value());
      if (ps.executeUpdate() != 1) {
        throw new SQLException("Article not found: " + articleId.value());
      }
    }
  }

  void deleteTrashEntry(ArticleId articleId) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "DELETE FROM article_trash WHERE article_id = ?")) {
      ps.setLong(1, articleId.value());
      ps.executeUpdate();
    }
  }

  void recordEvent(String kind, Long articleId, String detail, Instant occurredAt) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO workspace_events(kind, article_id, detail, occurred_at) VALUES(?, ?, ?, ?)")) {
      ps.setString(1, kind);
      if (articleId == null) ps.setObject(2, null); else ps.setLong(2, articleId);
      ps.setString(3, detail);
      ps.setString(4, occurredAt.toString());
      ps.executeUpdate();
    }
  }

  List<WorkspaceEventRow> recentEvents(int limit) throws SQLException {
    List<WorkspaceEventRow> result = new ArrayList<>();
    try (PreparedStatement ps = readConnection().prepareStatement(
        "SELECT id, kind, article_id, detail, occurred_at FROM workspace_events ORDER BY id DESC LIMIT ?")) {
      ps.setInt(1, Math.max(1, Math.min(limit, 500)));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.add(new WorkspaceEventRow(rs.getLong(1), rs.getString(2),
            (Long) rs.getObject(3), rs.getString(4), rs.getString(5)));
      }
    }
    return result;
  }

  void deleteDocumentProjectionData(long documentId) throws SQLException {
    try (PreparedStatement chunks = connection.prepareStatement("DELETE FROM chunks WHERE document_id = ?");
        PreparedStatement tags = connection.prepareStatement("DELETE FROM document_tags WHERE document_id = ?")) {
      chunks.setLong(1, documentId);
      chunks.executeUpdate();
      tags.setLong(1, documentId);
      tags.executeUpdate();
    }
  }

  private void deleteChunks(long documentId) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM chunks WHERE document_id = ?")) {
      ps.setLong(1, documentId);
      ps.executeUpdate();
    }
  }

  private void migrateMetadataSchema() throws SQLException {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement st = connection.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS schema_migrations (
            version TEXT PRIMARY KEY,
            applied_at TEXT NOT NULL
          )
          """);
      try (PreparedStatement marker =
          connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
        marker.setString(1, "document-metadata-v1");
        try (ResultSet rs = marker.executeQuery()) {
          if (rs.next()) {
            connection.commit();
            return;
          }
        }
      }

      List<String> documentPaths = new ArrayList<>();
      try (Statement documents = connection.createStatement();
          ResultSet rs = documents.executeQuery(
              "SELECT id, path, summary, summary_model FROM documents ORDER BY id")) {
        while (rs.next()) {
          long documentId = rs.getLong("id");
          String path = rs.getString("path");
          documentPaths.add(path);
          String title = firstChunkTitle(documentId).orElseGet(() -> fileName(path));
          if (!title.isBlank()) {
            insertFallbackMetadata(documentId, "title", title, null);
          }
        }
      }
      boolean projectionAlreadyQueued = !findPendingProjectionBatches().isEmpty();
      if (!documentPaths.isEmpty() && !projectionAlreadyQueued) {
        createProjectionBatch("metadata-migration-v1", documentPaths);
      }
      try (PreparedStatement marker =
          connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)")) {
        marker.setString(1, "document-metadata-v1");
        marker.setString(2, Instant.now().toString());
        marker.executeUpdate();
      }
      connection.commit();
    } catch (SQLException error) {
      connection.rollback();
      throw error;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  private void migrateArticleRevisionSchema() throws SQLException {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement st = connection.createStatement()) {
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS articles (
            article_id INTEGER PRIMARY KEY,
            document_id INTEGER NOT NULL UNIQUE,
            current_revision_number INTEGER NOT NULL,
            state TEXT NOT NULL,
            projection_state TEXT NOT NULL,
            projection_revision_number INTEGER NOT NULL,
            projection_failure_reason TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS article_revisions (
            article_id INTEGER NOT NULL,
            revision_number INTEGER NOT NULL,
            content_hash TEXT NOT NULL,
            format TEXT NOT NULL,
            source_path TEXT NOT NULL,
            source_kind TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(article_id, revision_number),
            FOREIGN KEY(article_id) REFERENCES articles(article_id) ON DELETE CASCADE
          )
          """);
      st.execute(
          """
          CREATE INDEX IF NOT EXISTS idx_article_revisions_source_path
          ON article_revisions(source_path)
          """);
      st.execute(
          """
          CREATE TABLE IF NOT EXISTS article_trash (
            article_id INTEGER PRIMARY KEY,
            original_path TEXT NOT NULL,
            trash_path TEXT NOT NULL,
            deleted_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            FOREIGN KEY(article_id) REFERENCES articles(article_id) ON DELETE CASCADE
          )
          """);
      st.execute("CREATE TABLE IF NOT EXISTS workspace_events ("
          + "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, article_id INTEGER, "
          + "detail TEXT NOT NULL, occurred_at TEXT NOT NULL)");

      try (PreparedStatement marker =
          connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
        marker.setString(1, "article-revisions-v1");
        try (ResultSet rs = marker.executeQuery()) {
          if (rs.next()) {
            connection.commit();
            return;
          }
        }
      }

      String now = Instant.now().toString();
      try (PreparedStatement articles = connection.prepareStatement(
              """
              INSERT INTO articles(
                  article_id, document_id, current_revision_number, state,
                  projection_state, projection_revision_number, created_at, updated_at)
              SELECT d.id, d.id, 1, 'ACTIVE', 'INDEXED', 1, ?, ?
              FROM documents d
              WHERE NOT EXISTS (
                SELECT 1 FROM articles a WHERE a.document_id = d.id)
              ORDER BY d.id
              """);
          PreparedStatement revisions = connection.prepareStatement(
              """
              INSERT INTO article_revisions(
                  article_id, revision_number, content_hash, format, source_path, source_kind, created_at)
              SELECT a.article_id, 1, d.hash, d.file_type, d.path,
                  CASE WHEN EXISTS (
                    SELECT 1 FROM document_uploads u WHERE u.document_id = d.id)
                    THEN 'UPLOADS' ELSE 'ROOT' END,
                  d.indexed_at
              FROM articles a
              JOIN documents d ON d.id = a.document_id
              WHERE NOT EXISTS (
                SELECT 1 FROM article_revisions r
                WHERE r.article_id = a.article_id AND r.revision_number = 1)
              ORDER BY a.article_id
              """)) {
        articles.setString(1, now);
        articles.setString(2, now);
        articles.executeUpdate();
        revisions.executeUpdate();
      }

      try (PreparedStatement marker =
          connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)")) {
        marker.setString(1, "article-revisions-v1");
        marker.setString(2, now);
        marker.executeUpdate();
      }
      connection.commit();
    } catch (SQLException error) {
      connection.rollback();
      throw error;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  private void insertFallbackMetadata(long documentId, String field, String value, String generatorModel)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            INSERT INTO document_metadata_values(
                document_id, field, value, source, generator_model, created_at, updated_at)
            SELECT ?, ?, ?, 'fallback', ?, ?, ?
            WHERE NOT EXISTS (
              SELECT 1 FROM document_metadata_values
              WHERE document_id = ? AND field = ? AND source = 'fallback')
            """)) {
      String now = Instant.now().toString();
      ps.setLong(1, documentId);
      ps.setString(2, field);
      ps.setString(3, value);
      ps.setString(4, generatorModel);
      ps.setString(5, now);
      ps.setString(6, now);
      ps.setLong(7, documentId);
      ps.setString(8, field);
      ps.executeUpdate();
    }
  }

  private ArticleData readArticleData(ResultSet rs) throws SQLException {
    ArticleRevision revision = readCurrentArticleRevision(rs);
    ProjectionStatus projection =
        new ProjectionStatus(
            ProjectionState.valueOf(rs.getString("projection_state")),
            rs.getLong("projection_revision_number"),
            Optional.ofNullable(rs.getString("projection_failure_reason")),
            Instant.parse(rs.getString("updated_at")));
    return new ArticleData(
        rs.getLong("article_id"),
        revision,
        ArticleState.valueOf(rs.getString("state")),
        projection);
  }

  private ArticleRevision readArticleRevision(ResultSet rs) throws SQLException {
    return new ArticleRevision(
        rs.getLong("revision_number"),
        rs.getString("content_hash"),
        rs.getString("format"),
        rs.getString("source_path"),
        ArticleSourceKind.valueOf(rs.getString("source_kind")),
        Instant.parse(rs.getString("created_at")));
  }

  private ArticleRevision readCurrentArticleRevision(ResultSet rs) throws SQLException {
    return new ArticleRevision(
        rs.getLong("current_revision_number"),
        rs.getString("content_hash"),
        rs.getString("format"),
        rs.getString("source_path"),
        ArticleSourceKind.valueOf(rs.getString("source_kind")),
        Instant.parse(rs.getString("created_at")));
  }

  private ArticleMetadata toArticleMetadata(EffectiveDocumentMetadata metadata) {
    return new ArticleMetadata(
        metadata.title().map(EffectiveMetadataValue::value),
        metadata.summary().map(EffectiveMetadataValue::value),
        metadata.keywords().stream().map(EffectiveMetadataValue::value).toList(),
        metadata.tags().stream().map(EffectiveMetadataValue::value).toList());
  }

  private Optional<String> firstChunkTitle(long documentId) throws SQLException {
    try (PreparedStatement ps =
        readConnection().prepareStatement(
            "SELECT title FROM chunks WHERE document_id = ? AND title IS NOT NULL AND TRIM(title) <> '' ORDER BY chunk_no LIMIT 1")) {
      ps.setLong(1, documentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString("title")) : Optional.empty();
      }
    }
  }

  private void touchSession(long sessionId, String timestamp) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            """
            UPDATE sessions
            SET updated_at = ?, last_message_at = ?
            WHERE id = ?
            """)) {
      ps.setString(1, timestamp);
      ps.setString(2, timestamp);
      ps.setLong(3, sessionId);
      ps.executeUpdate();
    }
  }

  private SessionRow readSessionRow(ResultSet rs) throws SQLException {
    return new SessionRow(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("created_at"),
        rs.getString("updated_at"),
        Optional.ofNullable(rs.getString("archived_at")),
        Optional.ofNullable(rs.getString("last_message_at")));
  }

  private SessionMessageRow readSessionMessageRow(ResultSet rs) throws SQLException {
    return new SessionMessageRow(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getInt("message_no"),
        rs.getString("role"),
        rs.getString("content"),
        rs.getString("created_at"));
  }

  private SessionSummaryRow readSessionSummaryRow(ResultSet rs) throws SQLException {
    return new SessionSummaryRow(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getInt("summary_no"),
        rs.getString("summary_text"),
        rs.getString("created_at"),
        rs.getString("updated_at"));
  }

  private DocumentRow readDocumentRow(ResultSet rs) throws SQLException {
    return new DocumentRow(
        rs.getLong("id"),
        rs.getString("root_path"),
        rs.getString("path"),
        rs.getString("file_type"),
        rs.getString("charset"),
        rs.getString("hash"),
        Optional.ofNullable(rs.getString("summary")),
        Optional.ofNullable(rs.getString("summary_model")),
        Optional.ofNullable(rs.getString("summary_updated_at")));
  }

  private ChunkRow readChunkRow(ResultSet rs) throws SQLException {
    return new ChunkRow(
        rs.getLong("id"), rs.getLong("document_id"), rs.getInt("chunk_no"), rs.getString("title"),
        rs.getString("content"), rs.getInt("start_line"), rs.getInt("end_line"),
        rs.getString("path"), rs.getString("root_path"));
  }

  private static String rsString(ResultSet rs, String column) {
    try {
      return rs.getString(column);
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  record DocumentRow(
      long id,
      String rootPath,
      String path,
      String fileType,
      String charset,
      String hash,
      Optional<String> summary,
      Optional<String> summaryModel,
      Optional<String> summaryUpdatedAt) {}

  public record WorkspaceEventRow(long id, String kind, Long articleId, String detail, String occurredAt) {}

  record DocumentPage(List<DocumentRow> documents, int total) {}

  private record ArticleData(
      long articleId, ArticleRevision revision, ArticleState state, ProjectionStatus projection) {}

  public record RootRow(String path, String lifecycle, Optional<String> reason, String updatedAt) {}

  record ProjectionBatchRow(long id, String businessCheckpoint, String createdAt, List<String> paths) {}

  private static final class ProjectionBatchRowBuilder {
    private final long id;
    private final String businessCheckpoint;
    private final String createdAt;
    private final List<String> paths = new ArrayList<>();

    private ProjectionBatchRowBuilder(long id, String businessCheckpoint, String createdAt) {
      this.id = id;
      this.businessCheckpoint = businessCheckpoint;
      this.createdAt = createdAt;
    }

    private ProjectionBatchRow build() {
      return new ProjectionBatchRow(id, businessCheckpoint, createdAt, List.copyOf(paths));
    }
  }

  record ChunkRow(
      long id,
      long documentId,
      int chunkNo,
      String title,
      String content,
      int startLine,
      int endLine,
      String path,
      String rootPath) {}

  record ChunkEmbeddingRow(
      long chunkId, String embeddingModel, String vectorJson, int vectorDim, String updatedAt) {}

  public record DocumentTagRow(long documentId, String tag, String source, String createdAt) {}

  public record DocumentMetadataRow(
      long documentId,
      String field,
      String value,
      String source,
      Optional<String> generatorModel,
      String createdAt,
      String updatedAt) {}

  public record EffectiveMetadataValue(
      String value, String source, Optional<String> generatorModel) {}

  public record EffectiveDocumentMetadata(
      long documentId,
      Optional<EffectiveMetadataValue> title,
      Optional<EffectiveMetadataValue> summary,
      List<EffectiveMetadataValue> tags,
      List<EffectiveMetadataValue> keywords) {}

  public record DocumentKeywordRow(long documentId, String keyword, String source, String createdAt) {}

  public record DocumentUploadRow(
      String contentHash,
      long documentId,
      String storagePath,
      String originalFilename,
      String relativePath,
      String batchId,
      String receivedAt) {}

  public record SessionRow(
      long id,
      String title,
      String createdAt,
      String updatedAt,
      Optional<String> archivedAt,
      Optional<String> lastMessageAt) {}

  public record SessionMessageRow(
      long id,
      long sessionId,
      int messageNo,
      String role,
      String content,
      String createdAt) {}

  public record SessionSummaryRow(
      long id,
      long sessionId,
      int summaryNo,
      String summaryText,
      String createdAt,
      String updatedAt) {}
}
