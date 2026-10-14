package me.asu.kb.runtime;

import java.util.Map;

/** Transport-independent boundary for the fresh-layout document and session module. */
public interface SingleWorkspaceDocumentApplication extends AutoCloseable {
  Object execute(Operation operation, Map<String, Object> arguments) throws Exception;

  enum Operation {
    INGEST_FILE,
    INGEST_DIRECTORY,
    REBUILD_INDEX,
    LIST_ROOTS,
    REMOVE_ROOT,
    LIST_DOCUMENTS,
    CREATE_DOCUMENT,
    GET_DOCUMENT,
    UPDATE_DOCUMENT,
    DELETE_DOCUMENT,
    DOCUMENT_REVISIONS,
    RESTORE_REVISION,
    LIST_TRASH,
    RESTORE_TRASH,
    ASK,
    LIST_SESSIONS,
    CREATE_SESSION,
    GET_SESSION,
    UPDATE_SESSION,
    DELETE_SESSION,
    LIST_MESSAGES,
    APPEND_MESSAGE,
    GET_SUMMARY,
    UPDATE_SUMMARY,
    EXPORT_SESSION,
    IMPORT_SESSION,
    CLEANUP,
    RECENT_EVENTS
  }

}
