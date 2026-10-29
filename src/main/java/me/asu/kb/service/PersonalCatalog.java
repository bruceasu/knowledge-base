package me.asu.kb.service;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

/** Additive personal catalog on the document repository's owned connection. */
final class PersonalCatalog {
  private static final int VERSION = 1;
  private final Connection connection;
  private final String serviceId;
  private final byte[] cursorKey;

  PersonalCatalog(Connection connection, Path backupFile) throws SQLException {
    this.connection = connection;
    boolean initialized;
    try (var statement = connection.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='personal_schema'")) {
      try (var result = statement.executeQuery()) { initialized = result.next(); }
    }
    if (initialized) {
      try (var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT version FROM personal_schema")) {
        if (!result.next() || result.getInt(1) != VERSION || result.next()) {
          throw new SQLException("unsupported personal schema version");
        }
      }
    } else {
      if (!connection.getAutoCommit()) throw new SQLException("migration requires an idle write connection");
      // VACUUM INTO includes committed WAL content; a raw main-file copy would not.
      try (var backup = connection.prepareStatement("VACUUM INTO ?")) {
        backup.setString(1, backupFile.toAbsolutePath().toString());
        backup.execute();
      }
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE personal_schema(version INTEGER NOT NULL)");
        statement.execute("INSERT INTO personal_schema VALUES(1)");
        statement.execute("CREATE TABLE personal_service(singleton INTEGER PRIMARY KEY CHECK(singleton=1), service_id TEXT NOT NULL UNIQUE, cursor_key TEXT NOT NULL)");
        statement.execute("CREATE TABLE personal_collections(id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        statement.execute("INSERT INTO personal_collections VALUES('default','资料'),('notes','笔记')");
        // Keep mappings/tombstones after a legacy document is deleted. No cascade.
        statement.execute("CREATE TABLE personal_documents(id TEXT PRIMARY KEY, document_id INTEGER UNIQUE, collection_id TEXT NOT NULL REFERENCES personal_collections(id), format TEXT NOT NULL, source_kind TEXT NOT NULL, original_path TEXT, extraction_state TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)");
        statement.execute("CREATE TABLE personal_operations(id TEXT PRIMARY KEY, subject TEXT NOT NULL, idempotency_key TEXT NOT NULL, request_json TEXT NOT NULL, request_hash TEXT NOT NULL, document_id TEXT NOT NULL UNIQUE, target_path TEXT NOT NULL UNIQUE, state TEXT NOT NULL CHECK(state IN ('pending','succeeded','failed')), index_state TEXT NOT NULL, result_json TEXT, error_code TEXT, created_at TEXT NOT NULL, UNIQUE(subject,idempotency_key))");
        statement.execute("CREATE TABLE personal_imports(id TEXT PRIMARY KEY, document_id TEXT NOT NULL UNIQUE, original_path TEXT NOT NULL UNIQUE, original_name TEXT NOT NULL, format TEXT NOT NULL, state TEXT NOT NULL, error_code TEXT, result_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        statement.execute("CREATE TABLE personal_citations(note_id TEXT NOT NULL, ordinal INTEGER NOT NULL, source_id TEXT NOT NULL, revision TEXT NOT NULL, location_json TEXT NOT NULL, snapshot_json TEXT NOT NULL, PRIMARY KEY(note_id,ordinal))");
        statement.execute("CREATE INDEX personal_documents_collection ON personal_documents(collection_id,deleted)");
        statement.execute("CREATE INDEX personal_operations_state ON personal_operations(state)");
        statement.execute("CREATE INDEX personal_imports_state ON personal_imports(state)");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (var insert = connection.prepareStatement("INSERT INTO personal_service VALUES(1,?,?)")) {
          insert.setString(1, "kb_" + UUID.randomUUID());
          insert.setString(2, Base64.getEncoder().encodeToString(key));
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      } finally {
        connection.setAutoCommit(true);
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT service_id,cursor_key FROM personal_service WHERE singleton=1");
        ResultSet result = statement.executeQuery()) {
      if (!result.next()) throw new SQLException("personal service identity is missing");
      serviceId = result.getString(1);
      try { cursorKey = Base64.getDecoder().decode(result.getString(2)); }
      catch (IllegalArgumentException invalid) { throw new SQLException("personal cursor key is invalid"); }
      if (serviceId == null || !serviceId.matches("kb_[0-9a-f-]{36}") || cursorKey.length != 32) {
        throw new SQLException("personal service identity is invalid");
      }
    }
  }

  String serviceId() { return serviceId; }
  byte[] cursorKey() { return cursorKey.clone(); }

  java.util.List<java.util.Map<String, Object>> query(String sql, Object... arguments) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, arguments);
      try (var result = statement.executeQuery()) {
        var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        var metadata = result.getMetaData();
        while (result.next()) {
          var row = new java.util.LinkedHashMap<String, Object>();
          for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(metadata.getColumnLabel(i), result.getObject(i));
          rows.add(row);
        }
        return rows;
      }
    }
  }

  int update(String sql, Object... arguments) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, arguments);
      return statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object[] arguments) throws SQLException {
    for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
  }
}
