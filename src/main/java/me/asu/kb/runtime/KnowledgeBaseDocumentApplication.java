package me.asu.kb.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asu.kb.service.ArticleRevision;
import me.asu.kb.service.KnowledgeBase;
import me.asu.kb.service.RevisionHistory;

/** Production document/session adapter over one fresh-layout {@link KnowledgeBase}. */
public final class KnowledgeBaseDocumentApplication
    implements SingleWorkspaceDocumentApplication {
  private final Path workspaceRoot;
  private final KnowledgeBase knowledgeBase;
  private final AtomicBoolean closed = new AtomicBoolean();

  public KnowledgeBaseDocumentApplication(Path workspaceRoot, KnowledgeBase knowledgeBase) {
    this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
    this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase");
  }

  @Override
  public Object execute(Operation operation, Map<String, Object> arguments) throws Exception {
    Objects.requireNonNull(operation, "operation");
    Map<String, Object> values = arguments == null ? Map.of() : arguments;
    if (closed.get()) {
      throw new IllegalStateException("document application is closed");
    }
    return switch (operation) {
      case INGEST_FILE -> complete("ingest-file", () -> knowledgeBase.ingest(confinedPath(values, "path")));
      case INGEST_DIRECTORY -> complete("ingest-directory", () -> knowledgeBase.sync(confinedPath(values, "path", "root")));
      case REBUILD_INDEX -> complete("rebuild", knowledgeBase::rebuild);
      case LIST_ROOTS -> Map.of("ok", true, "operation", "root-list", "roots", knowledgeBase.roots());
      case REMOVE_ROOT -> removeRoot(values);
      case LIST_DOCUMENTS -> knowledgeBase.documents(integer(values, "limit", 50), integer(values, "offset", 0), text(values, "query", null));
      case CREATE_DOCUMENT -> knowledgeBase.createDocument(requiredText(values, "filename"), requiredText(values, "content"), text(values, "title", null), strings(values, "tags"), strings(values, "keywords"), text(values, "summary", null));
      case GET_DOCUMENT -> knowledgeBase.document(requiredLong(values, "id"));
      case UPDATE_DOCUMENT -> knowledgeBase.saveDocument(requiredLong(values, "id"), requiredText(values, "content"), text(values, "expectedBaseHash", null), text(values, "title", null), strings(values, "tags"), strings(values, "keywords"), text(values, "summary", null));
      case DELETE_DOCUMENT -> knowledgeBase.deleteDocument(requiredLong(values, "id"));
      case DOCUMENT_REVISIONS -> documentRevisions(values);
      case RESTORE_REVISION -> knowledgeBase.restoreRevision(requiredLong(values, "id"), requiredLong(values, "revision"));
      case LIST_TRASH -> Map.of("ok", true, "operation", "trash", "entries", knowledgeBase.trashEntries());
      case RESTORE_TRASH -> knowledgeBase.restoreDocument(requiredLong(values, "id"));
      case ASK -> knowledgeBase.ask(optionalLong(values, "sessionId"), requiredText(values, "question"), integer(values, "topK", 5));
      case LIST_SESSIONS -> Map.of("ok", true, "operation", "session-list", "sessions", knowledgeBase.sessions().list());
      case CREATE_SESSION -> sessionCreate(values);
      case GET_SESSION -> sessionGet(values);
      case UPDATE_SESSION -> sessionUpdate(values);
      case DELETE_SESSION -> sessionDelete(values);
      case LIST_MESSAGES -> Map.of("ok", true, "operation", "session-messages", "sessionId", requiredLong(values, "id"), "messages", knowledgeBase.sessions().messages(requiredLong(values, "id")));
      case APPEND_MESSAGE -> sessionAppend(values);
      case GET_SUMMARY -> Map.of("ok", true, "operation", "session-summary", "sessionId", requiredLong(values, "id"), "summaries", knowledgeBase.sessions().summaries(requiredLong(values, "id")));
      case UPDATE_SUMMARY -> sessionSummary(values);
      case EXPORT_SESSION -> sessionExport(values);
      case IMPORT_SESSION -> sessionImport(values);
      case CLEANUP -> knowledgeBase.cleanupRetention(Instant.now());
      case RECENT_EVENTS -> Map.of("ok", true, "operation", "events", "events", knowledgeBase.recentEvents(integer(values, "limit", 100)));
    };
  }

  private Object documentRevisions(Map<String, Object> values) throws Exception {
    long documentId = requiredLong(values, "id");
    RevisionHistory history = knowledgeBase.revisionHistory(documentId);
    List<Map<String, Object>> revisions =
        history.revisions().stream().map(KnowledgeBaseDocumentApplication::revisionPayload).toList();
    return Map.of(
        "ok", true,
        "operation", "document-revisions",
        "documentId", documentId,
        "revisions", revisions);
  }

  private static Map<String, Object> revisionPayload(ArticleRevision revision) {
    return Map.of(
        "revision", revision.revisionNumber(),
        "contentHash", revision.contentHash(),
        "format", revision.format(),
        "sourcePath", revision.sourcePath(),
        "sourceKind", revision.sourceKind().name(),
        "createdAt", revision.createdAt());
  }

  private Object removeRoot(Map<String, Object> values) throws Exception {
    Path root = confinedPath(values, "root", "path");
    String mode = text(values, "mode", "detach");
    KnowledgeBase.RootRemovalMode removalMode;
    try {
      removalMode = KnowledgeBase.RootRemovalMode.valueOf(mode.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("root mode must be detach or purge", error);
    }
    knowledgeBase.removeRoot(root, removalMode, bool(values, "confirmed", false));
    return Map.of("ok", true, "operation", "root-remove", "root", root.toString(), "mode", mode.toLowerCase(Locale.ROOT));
  }

  private Object sessionCreate(Map<String, Object> values) throws Exception {
    long id = knowledgeBase.sessions().create(text(values, "title", null));
    return Map.of("ok", true, "operation", "session-create", "session", knowledgeBase.sessions().findById(id).orElseThrow());
  }

  private Object sessionGet(Map<String, Object> values) throws Exception {
    return Map.of("ok", true, "operation", "session-get", "session", knowledgeBase.sessions().findById(requiredLong(values, "id")).orElseThrow());
  }

  private Object sessionUpdate(Map<String, Object> values) throws Exception {
    long id = requiredLong(values, "id");
    String title = text(values, "title", null);
    if (title != null && !title.isBlank()) knowledgeBase.sessions().rename(id, title);
    if (bool(values, "archived", false)) knowledgeBase.sessions().archive(id);
    return sessionGet(Map.of("id", id));
  }

  private Object sessionDelete(Map<String, Object> values) throws Exception {
    long id = requiredLong(values, "id");
    knowledgeBase.sessions().delete(id);
    return Map.of("ok", true, "operation", "session-delete", "sessionId", id);
  }

  private Object sessionAppend(Map<String, Object> values) throws Exception {
    long id = requiredLong(values, "id");
    String role = text(values, "role", "user");
    String content = requiredText(values, "content");
    if ("assistant".equalsIgnoreCase(role)) knowledgeBase.sessions().appendAssistantMessage(id, content);
    else if ("user".equalsIgnoreCase(role)) knowledgeBase.sessions().appendUserMessage(id, content);
    else throw new IllegalArgumentException("role must be user or assistant");
    return Map.of("ok", true, "operation", "session-message-create", "sessionId", id, "messages", knowledgeBase.sessions().recentMessages(id, 1));
  }

  private Object sessionSummary(Map<String, Object> values) throws Exception {
    long id = requiredLong(values, "id");
    knowledgeBase.sessions().upsertSummary(id, requiredText(values, "summary", "summaryText"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("operation", "session-summary-update");
    result.put("sessionId", id);
    result.put("summary", knowledgeBase.sessions().latestSummaryRow(id).orElse(null));
    return result;
  }

  private Object sessionExport(Map<String, Object> values) throws Exception {
    long id = requiredLong(values, "id");
    return Map.of(
        "ok", true,
        "operation", "session-export",
        "session", knowledgeBase.sessions().findById(id).orElseThrow(),
        "messages", knowledgeBase.sessions().messages(id),
        "summaries", knowledgeBase.sessions().summaries(id));
  }

  private Object sessionImport(Map<String, Object> values) throws Exception {
    Map<String, Object> session = map(values.get("session"));
    long id = knowledgeBase.sessions().create(text(session, "title", null));
    for (Map<String, Object> message : maps(values.get("messages"))) {
      String role = text(message, "role", "user");
      String content = requiredText(message, "content");
      if ("assistant".equalsIgnoreCase(role)) knowledgeBase.sessions().appendAssistantMessage(id, content);
      else if ("user".equalsIgnoreCase(role)) knowledgeBase.sessions().appendUserMessage(id, content);
      else throw new IllegalArgumentException("role must be user or assistant");
    }
    for (Map<String, Object> summary : maps(values.get("summaries"))) {
      String content = requiredText(summary, "summaryText", "summary");
      Long number = optionalLong(summary, "summaryNo");
      if (number == null) knowledgeBase.sessions().upsertSummary(id, content);
      else knowledgeBase.sessions().upsertSummary(id, Math.toIntExact(number), content);
    }
    return sessionExport(Map.of("id", id));
  }

  private Object complete(String operation, CheckedRunnable runnable) throws Exception {
    runnable.run();
    return Map.of("ok", true, "operation", operation);
  }

  private Path confinedPath(Map<String, Object> values, String... names) {
    String value = requiredText(values, names);
    Path supplied = Path.of(value);
    Path resolved = supplied.isAbsolute() ? supplied.normalize() : workspaceRoot.resolve(supplied).normalize();
    if (!resolved.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("path must remain inside the service workspace");
    }
    return resolved;
  }

  private static String requiredText(Map<String, Object> values, String... names) {
    for (String name : names) {
      String value = text(values, name, null);
      if (value != null && !value.isBlank()) return value;
    }
    throw new IllegalArgumentException(String.join("/", names) + " is required");
  }

  private static String text(Map<String, Object> values, String name, String fallback) {
    Object value = values.get(name);
    return value == null ? fallback : String.valueOf(value);
  }

  private static long requiredLong(Map<String, Object> values, String name) {
    Long value = optionalLong(values, name);
    if (value == null) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static Long optionalLong(Map<String, Object> values, String name) {
    Object value = values.get(name);
    if (value == null || String.valueOf(value).isBlank()) return null;
    if (value instanceof Number number) return number.longValue();
    try { return Long.parseLong(String.valueOf(value)); }
    catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be an integer", error); }
  }

  private static int integer(Map<String, Object> values, String name, int fallback) {
    Long value = optionalLong(values, name);
    return value == null ? fallback : Math.toIntExact(value);
  }

  private static boolean bool(Map<String, Object> values, String name, boolean fallback) {
    Object value = values.get(name);
    if (value == null) return fallback;
    if (value instanceof Boolean booleanValue) return booleanValue;
    if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
    if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
    throw new IllegalArgumentException(name + " must be a boolean");
  }

  private static List<String> strings(Map<String, Object> values, String name) {
    Object value = values.get(name);
    if (value == null) return null;
    if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
    String text = String.valueOf(value);
    if (text.isBlank()) return List.of();
    return List.of(text.split(",")).stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("session must be an object");
    return (Map<String, Object>) raw;
  }

  private static List<Map<String, Object>> maps(Object value) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) throw new IllegalArgumentException("import collection must be an array");
    return list.stream().map(KnowledgeBaseDocumentApplication::map).toList();
  }

  @Override
  public void close() throws Exception {
    if (closed.compareAndSet(false, true)) knowledgeBase.close();
  }

  @FunctionalInterface
  private interface CheckedRunnable { void run() throws Exception; }
}
