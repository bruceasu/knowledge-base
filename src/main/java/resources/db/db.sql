CREATE TABLE
    IF NOT EXISTS documents (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        root_path TEXT NOT NULL,
        path TEXT NOT NULL UNIQUE,
        file_type TEXT NOT NULL,
        charset TEXT,
        hash TEXT NOT NULL,
        indexed_at TEXT NOT NULL
    );

CREATE TABLE
    IF NOT EXISTS chunks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        document_id INTEGER NOT NULL,
        chunk_no INTEGER NOT NULL,
        title TEXT,
        content TEXT NOT NULL,
        start_line INTEGER NOT NULL,
        end_line INTEGER NOT NULL,
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS document_tags (
        document_id INTEGER NOT NULL,
        tag TEXT NOT NULL,
        source TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY (document_id, tag, source),
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS document_metadata_values (
        document_id INTEGER NOT NULL,
        field TEXT NOT NULL,
        value TEXT NOT NULL,
        source TEXT NOT NULL,
        generator_model TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (document_id, field, source),
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS document_keywords (
        document_id INTEGER NOT NULL,
        keyword TEXT NOT NULL,
        source TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY (document_id, keyword, source),
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS document_uploads (
        content_hash TEXT PRIMARY KEY,
        document_id INTEGER NOT NULL,
        storage_path TEXT NOT NULL UNIQUE,
        original_filename TEXT,
        relative_path TEXT,
        batch_id TEXT,
        received_at TEXT NOT NULL,
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS chunk_embeddings (
        chunk_id INTEGER PRIMARY KEY,
        embedding_model TEXT NOT NULL,
        vector_json TEXT NOT NULL,
        vector_dim INTEGER NOT NULL,
        updated_at TEXT NOT NULL,
        FOREIGN KEY (chunk_id) REFERENCES chunks (id) ON DELETE CASCADE
    );

CREATE TABLE
    IF NOT EXISTS sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        archived_at TEXT,
        last_message_at TEXT
    );

CREATE TABLE
    IF NOT EXISTS session_messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id INTEGER NOT NULL,
        message_no INTEGER NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        created_at TEXT NOT NULL,
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE,
        UNIQUE (session_id, message_no)
    );

CREATE TABLE
    IF NOT EXISTS session_summaries (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id INTEGER NOT NULL,
        summary_no INTEGER NOT NULL,
        summary_text TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE,
        UNIQUE (session_id, summary_no)
    );

CREATE TABLE
    IF NOT EXISTS schema_migrations (
        version TEXT PRIMARY KEY,
        applied_at TEXT NOT NULL
    );
