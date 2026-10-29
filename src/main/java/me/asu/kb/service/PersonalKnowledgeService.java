package me.asu.kb.service;

import static me.asu.kb.service.PersonalValues.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.asu.kb.ingest.ArchiveBudget;
import me.asu.kb.ingest.PersonalTextExtractor;
import me.asu.kb.runtime.PersonalKnowledgeApplication;
import me.asu.kb.runtime.PersonalKnowledgeSettings;

/** Single-workspace application. All catalog/legacy operations share the KB monitor. */
final class PersonalKnowledgeService implements PersonalKnowledgeApplication {
  private final KnowledgeBase kb;
  private final KnowledgeRepository repository;
  private final PersonalCatalog catalog;
  private final PersonalReadService reader;
  private final Path root;
  private final PersonalKnowledgeSettings settings;
  private final java.util.concurrent.ScheduledExecutorService worker;
  private volatile boolean closed;
  private final java.util.concurrent.Semaphore uploads = new java.util.concurrent.Semaphore(2);

  PersonalKnowledgeService(KnowledgeBase kb, KnowledgeRepository repository, PersonalCatalog catalog,
      LuceneSearchIndex index, Path workspace, PersonalKnowledgeSettings settings) throws Exception {
    this.kb = kb; this.repository = repository; this.catalog = catalog; this.settings = settings;
    root = workspace.resolve(".kb/personal");
    for (String dir : List.of("originals", "text", "notes", "temporary")) Files.createDirectories(root.resolve(dir));
    reader = new PersonalReadService(kb, repository, catalog, index, settings.publicBaseUrl().toString());
    reader.mapExisting();
    try (var files = Files.list(root.resolve("temporary"))) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) Files.deleteIfExists(file);
    }
    worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "personal-import-worker"); t.setDaemon(true); return t; });
    worker.scheduleWithFixedDelay(this::processOne, 1, 1, TimeUnit.SECONDS);
  }

  @Override public Result execute(Operation operation, Caller caller, Map<String, Object> request) throws Exception {
    synchronized (kb) {
      if (closed) throw new Failure(503, "UNAVAILABLE");
      String scope = switch (operation) { case CREATE_NOTE, CREATION_STATUS -> "knowledge:note:create"; default -> "knowledge:read"; };
      if (!caller.scopes().contains(scope)) throw new Failure(403, "FORBIDDEN");
      return switch (operation) {
        case CAPABILITIES -> {
          fields(request);
          yield new Result(200, Map.of("serviceId", catalog.serviceId(), "capabilities", List.of("search", "read", "create_note", "creation_status"),
              "limits", Map.of("maxSearchLimit", 20, "maxExcerptChars", 2000, "maxReadChars", 20000, "maxNoteChars", 100000), "publicBaseUrl", settings.publicBaseUrl().toString()));
        }
        case SEARCH -> {
          try { yield new Result(200, reader.search(caller, request)); }
          catch (Failure known) { throw known; }
          catch (org.apache.lucene.queryparser.classic.ParseException invalidQuery) { throw invalid(); }
          catch (Exception unavailable) { throw new Failure(503, "UNAVAILABLE"); }
        }
        case READ -> new Result(200, reader.read(caller, request));
        case BROWSER_READ -> {
          if (!caller.subject().equals("browser")) throw new Failure(403, "FORBIDDEN");
          var values = new LinkedHashMap<>(request);
          if (!values.containsKey("revision")) values.put("revision", reader.metadata(reader.row(text(values, "documentId", 1, 100), caller)).get("revision"));
          yield new Result(200, reader.read(caller, values));
        }
        case RECENT -> {
          if (!caller.subject().equals("browser")) throw new Failure(403, "FORBIDDEN");
          fields(request); reader.mapExisting();
          var items = new ArrayList<Map<String, Object>>();
          for (var row : catalog.query("SELECT * FROM personal_documents WHERE deleted=0 AND document_id IS NOT NULL ORDER BY updated_at DESC LIMIT 50")) {
            try { reader.row((String) row.get("id"), caller); items.add(reader.metadata(row)); }
            catch (Failure missing) { if (missing.status() != 404) throw missing; }
          }
          yield new Result(200, Map.of("items", items));
        }
        case CREATE_NOTE -> create(caller, request);
        case CREATION_STATUS -> { fields(request, "key"); yield new Result(200, receipt(findOperation(caller, text(request, "key", 36, 36)))); }
        case LIST_IMPORTS -> {
          if (!caller.subject().equals("browser")) throw new Failure(403, "FORBIDDEN");
          fields(request);
          var items = catalog.query("SELECT id,document_id AS documentId,original_name AS filename,format,state,error_code AS error,result_json,created_at AS createdAt FROM personal_imports ORDER BY created_at DESC LIMIT 100");
          for (var item : items) { Object diagnostic = item.remove("result_json"); item.put("diagnostics", diagnostic == null ? List.of() : parse((String) diagnostic).getOrDefault("diagnostics", List.of())); }
          yield new Result(200, Map.of("items", items));
        }
        case RETRY_IMPORT -> {
          if (!caller.subject().equals("browser")) throw new Failure(403, "FORBIDDEN");
          fields(request, "id"); String id = text(request, "id", 1, 100);
          queueCapacity();
          int changed = catalog.update("UPDATE personal_imports SET state='pending',error_code=NULL WHERE id=? AND state IN ('failed','unsupported','partial')", id);
          if (changed == 0) throw new Failure(409, "INVALID_STATE");
          yield new Result(202, Map.of("id", id, "state", "pending"));
        }
      };
    }
  }

  private Result create(Caller caller, Map<String, Object> input) throws Exception {
    fields(input, "key", "title", "content", "format", "tags", "collectionId", "citations");
    String key = text(input, "key", 36, 36);
    try { if (!UUID.fromString(key).toString().equalsIgnoreCase(key)) throw invalid(); } catch (IllegalArgumentException e) { throw invalid(); }
    if (!(input.get("title") instanceof String rawTitle)) throw invalid();
    String title = text(Map.of("title", rawTitle.strip()), "title", 1, 200); if (title.isBlank()) throw invalid();
    String content = text(input, "content", 1, 100000);
    if (!"markdown".equals(input.get("format"))) throw invalid();
    List<String> tags = strings(input, "tags", 20, 100);
    String collection = input.get("collectionId") == null ? "notes" : text(input, "collectionId", 1, 100);
    if (!caller.allows(collection) || catalog.query("SELECT id FROM personal_collections WHERE id=?", collection).isEmpty()) throw new Failure(404, "NOT_FOUND");
    Object rawCitations = input.getOrDefault("citations", List.of());
    if (!(rawCitations instanceof List<?> citations) || citations.size() > 20) throw invalid();
    var normalized = new LinkedHashMap<String, Object>();
    normalized.put("title", title); normalized.put("content", content); normalized.put("format", "markdown");
    var normalizedCitations = new ArrayList<Map<String, Object>>();
    for (Object raw : citations) {
      var citation = map(raw); fields(citation, "documentId", "revision", "location");
      var position = citation.containsKey("location") ? map(citation.get("location")) : location();
      validateLocation(position);
      var filled = new LinkedHashMap<>(position);
      for (String field : List.of("page", "startLine", "endLine")) filled.putIfAbsent(field, null);
      normalizedCitations.add(Map.of("documentId", text(citation, "documentId", 1, 100), "revision", text(citation, "revision", 1, 200), "location", filled));
    }
    normalized.put("tags", tags); normalized.put("collectionId", collection); normalized.put("citations", normalizedCitations);
    String canonical = json(canonical(normalized)), digest = hash(canonical);
    var existing = catalog.query("SELECT * FROM personal_operations WHERE subject=? AND idempotency_key=?", caller.subject(), key);
    if (!existing.isEmpty()) {
      if (!digest.equals(existing.get(0).get("request_hash"))) throw new Failure(409, "IDEMPOTENCY_CONFLICT");
      return new Result("pending".equals(existing.get(0).get("state")) ? 202 : 200, receipt(existing.get(0)));
    }
    queueCapacity();
    var snapshots = new ArrayList<Map<String, Object>>();
    for (Object raw : normalizedCitations) {
      if (!caller.scopes().contains("knowledge:read")) throw new Failure(403, "FORBIDDEN");
      var citation = map(raw); fields(citation, "documentId", "revision", "location");
      String sourceId = text(citation, "documentId", 1, 100), revision = text(citation, "revision", 1, 200);
      var source = reader.metadata(reader.row(sourceId, caller));
      if (!revision.equals(source.get("revision"))) throw new Failure(409, "CITATION_CHANGED");
      try { reader.read(caller, Map.of("documentId", sourceId, "revision", revision, "maxChars", 1000)); }
      catch (Failure invalidSource) {
        if (invalidSource.status() == 404) throw invalidSource;
        throw new Failure(409, "CITATION_CHANGED");
      }
      Map<String, Object> location = citation.containsKey("location") ? map(citation.get("location")) : location();
      validateLocation(location);
      snapshots.add(Map.of("sourceId", sourceId, "revision", revision, "location", location, "snapshot", source));
    }
    String id = "op_" + UUID.randomUUID(), doc = "doc_" + UUID.randomUUID();
    repository.inTransaction(() -> {
      catalog.update("INSERT INTO personal_operations(id,subject,idempotency_key,request_json,request_hash,document_id,target_path,state,index_state,created_at) VALUES(?,?,?,?,?,?,?,'pending','pending',?)",
          id, caller.subject(), key, canonical, digest, doc, "notes/" + doc + ".md", Instant.now().toString());
      for (int i = 0; i < snapshots.size(); i++) {
        var snapshot = snapshots.get(i);
        catalog.update("INSERT INTO personal_citations VALUES(?,?,?,?,?,?)", doc, i, snapshot.get("sourceId"), snapshot.get("revision"), json(snapshot.get("location")), json(snapshot.get("snapshot")));
      }
      return null;
    });
    return new Result(202, receipt(findOperation(caller, key)));
  }

  private static void validateLocation(Map<String, Object> value) {
    fields(value, "kind", "label", "page", "startLine", "endLine");
    if (!Set.of("document", "page", "section", "lines").contains(text(value, "kind", 1, 20))) throw invalid();
    text(value, "label", 1, 500);
    for (String key : List.of("page", "startLine", "endLine")) if (value.get(key) != null) integer(value, key, 0, 1, Integer.MAX_VALUE);
    if (value.get("startLine") != null && value.get("endLine") != null
        && ((Number) value.get("endLine")).longValue() < ((Number) value.get("startLine")).longValue()) throw invalid();
  }

  private Map<String, Object> findOperation(Caller caller, String key) throws Exception {
    var rows = catalog.query("SELECT * FROM personal_operations WHERE subject=? AND idempotency_key=?", caller.subject(), key);
    if (rows.isEmpty()) throw new Failure(404, "CREATION_NOT_FOUND");
    return rows.get(0);
  }
  private Map<String, Object> receipt(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    result.put("idempotencyKey", row.get("idempotency_key")); result.put("operationId", row.get("id"));
    result.put("state", row.get("state")); result.put("indexState", row.get("index_state"));
    result.put("documentId", "succeeded".equals(row.get("state")) ? row.get("document_id") : null); result.put("revision", null); result.put("url", null);
    result.put("error", row.get("error_code") == null ? null : Map.of("code", row.get("error_code"), "message", "保存失败", "retryable", false));
    if (row.get("result_json") != null) result.putAll(parse((String) row.get("result_json")));
    return result;
  }

  @Override public Result upload(Caller caller, String filename, InputStream input) throws Exception {
    if (!caller.subject().equals("browser") || !caller.allows("default")) throw new Failure(403, "FORBIDDEN");
    if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(c -> c < 32)) throw invalid();
    synchronized (kb) { queueCapacity(); }
    if (!uploads.tryAcquire()) throw new Failure(429, "RATE_LIMITED");
    String id = "import_" + UUID.randomUUID(), doc = "doc_" + UUID.randomUUID();
    Path temporary = root.resolve("temporary/" + id + ".tmp"), target = root.resolve("originals/" + doc);
    boolean accepted = false;
    try {
      long size = 0;
      try (var output = Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW)) {
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
          size += count; if (size > ArchiveBudget.MAX_UPLOAD_BYTES) throw new Failure(413, "PAYLOAD_TOO_LARGE");
          output.write(buffer, 0, count);
        }
      }
      synchronized (kb) {
        queueCapacity(); checkQuota(0);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        String now = Instant.now().toString();
        repository.inTransaction(() -> {
          catalog.update("INSERT INTO personal_imports(id,document_id,original_path,original_name,format,state,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?)",
              id, doc, "originals/" + doc, filename, PersonalReadService.format(PersonalTextExtractor.extension(filename)), now, now);
          catalog.update("INSERT INTO personal_documents(id,collection_id,format,source_kind,original_path,extraction_state,created_at,updated_at) VALUES(?,'default',?,'import',?,'pending',?,?)",
              doc, PersonalReadService.format(PersonalTextExtractor.extension(filename)), "originals/" + doc, now, now);
          return null;
        });
        accepted = true;
      }
      return new Result(202, Map.of("id", id, "documentId", doc, "state", "pending"));
    } finally {
      try { Files.deleteIfExists(temporary); if (!accepted) Files.deleteIfExists(target); }
      finally { uploads.release(); }
    }
  }

  private void queueCapacity() throws Exception {
    if (closed) throw new Failure(503, "UNAVAILABLE");
    long pending = ((Number) catalog.query("SELECT (SELECT COUNT(*) FROM personal_operations WHERE state='pending') + (SELECT COUNT(*) FROM personal_imports WHERE state='pending') AS n").get(0).get("n")).longValue();
    if (pending >= 128) throw new Failure(429, "RATE_LIMITED");
  }
  private void checkQuota(long extra) throws Exception {
    if (settings.storageQuotaBytes() == 0) return;
    long used = extra;
    try (var paths = Files.walk(root)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        used += Files.size(file); if (used > settings.storageQuotaBytes()) throw new Failure(413, "STORAGE_QUOTA_EXCEEDED");
      }
    }
  }

  private void processOne() {
    synchronized (kb) {
      if (closed) return;
      try {
        var notes = catalog.query("SELECT * FROM personal_operations WHERE state='pending' ORDER BY created_at LIMIT 1");
        if (!notes.isEmpty()) { saveNote(notes.get(0)); return; }
        var imports = catalog.query("SELECT * FROM personal_imports WHERE state='pending' ORDER BY created_at LIMIT 1");
        if (!imports.isEmpty()) importFile(imports.get(0));
      } catch (Exception failure) {
        // Leave durable pending state for the next recovery attempt; never print private content.
        System.err.println("personal worker could not update durable state");
      }
    }
  }

  private void saveNote(Map<String, Object> operation) throws Exception {
    String doc = (String) operation.get("document_id");
    Path target = managed((String) operation.get("target_path"));
    var request = parse((String) operation.get("request_json"));
    try {
      String content = (String) request.get("content");
      if (!catalog.query("SELECT id FROM personal_documents WHERE id=? AND deleted=1", doc).isEmpty()) throw new Failure(404, "NOT_FOUND");
      var previous = repository.findDocumentByPath(target.toAbsolutePath().toString());
      if (previous.isPresent() && repository.findArticleById(previous.get().id()).map(a -> !a.state().name().equals("ACTIVE")).orElse(true)) throw new Failure(404, "NOT_FOUND");
      if (!Files.exists(target)) { checkQuota(content.getBytes(StandardCharsets.UTF_8).length); new AtomicTextFileWriter().write(target, content); }
      else if (!Files.readString(target).equals(content)) throw new Failure(409, "CONTENT_CONFLICT");
      long internal;
      try { internal = kb.ingestPersonal(target, (String) request.get("title")); }
      catch (Exception indexing) {
        var persisted = repository.findDocumentByPath(target.toAbsolutePath().toString());
        if (persisted.isEmpty()) throw indexing;
        internal = persisted.get().id();
      }
      repository.inTransaction(() -> {
        catalog.update("INSERT OR IGNORE INTO personal_documents(id,collection_id,format,source_kind,extraction_state,created_at,updated_at) VALUES(?,?,'markdown','note','ready',?,?)", doc, request.get("collectionId"), operation.get("created_at"), Instant.now().toString());
        return null;
      });
      catalog.update("UPDATE personal_documents SET document_id=? WHERE id=?", internal, doc);
      kb.personalMetadata(internal, (String) request.get("title"), strings(request, "tags", 20, 100));
      boolean indexed = true;
      try { kb.reprojectDocument(internal); } catch (Exception indexFailure) { indexed = false; }
      var row = catalog.query("SELECT * FROM personal_documents WHERE id=?", doc).get(0);
      var metadata = reader.metadata(row);
      String indexState = indexed ? "ready" : "failed";
      catalog.update("UPDATE personal_operations SET state='succeeded',index_state=?,result_json=? WHERE id=?", indexState,
          json(Map.of("revision", metadata.get("revision"), "url", ((Map<?, ?>) metadata.get("source")).get("url"))), operation.get("id"));
    } catch (Exception failure) {
      catalog.update("UPDATE personal_operations SET state='failed',index_state='failed',error_code='SAVE_FAILED' WHERE id=?", operation.get("id"));
    }
  }

  private void importFile(Map<String, Object> task) throws Exception {
    String doc = (String) task.get("document_id"), state = "ready", error = null;
    try {
      if (!catalog.query("SELECT id FROM personal_documents WHERE id=? AND deleted=1", doc).isEmpty()) throw new Failure(404, "NOT_FOUND");
      Path original = managed((String) task.get("original_path"));
      String content = new PersonalTextExtractor().extract(original, (String) task.get("original_name"));
      var diagnostics = new PersonalTextExtractor().diagnostics(original, (String) task.get("original_name"));
      catalog.update("UPDATE personal_imports SET result_json=? WHERE id=?", json(Map.of("diagnostics", diagnostics)), task.get("id"));
      if (!diagnostics.isEmpty()) { state = "partial"; error = "PARTIAL_CONTENT"; }
      Path textFile = root.resolve("text/" + doc + ".txt");
      if (!Files.exists(textFile)) { checkQuota(content.getBytes(StandardCharsets.UTF_8).length); new AtomicTextFileWriter().write(textFile, content); }
      long internal;
      try { internal = kb.ingestPersonal(textFile, (String) task.get("original_name")); }
      catch (Exception indexFailure) {
        var persisted = repository.findDocumentByPath(textFile.toAbsolutePath().toString());
        if (persisted.isEmpty()) throw indexFailure;
        internal = persisted.get().id(); state = "partial"; error = "PARTIAL_INDEX";
      }
      catalog.update("UPDATE personal_documents SET document_id=?,extraction_state='ready',updated_at=? WHERE id=?", internal, Instant.now().toString(), doc);
    } catch (PersonalTextExtractor.UnsupportedFormat unsupported) { state = "unsupported"; error = "UNSUPPORTED_CONTENT"; }
    catch (Exception failure) { state = "failed"; error = "EXTRACTION_FAILED"; }
    if (state.equals("failed") || state.equals("unsupported")) catalog.update("UPDATE personal_documents SET extraction_state=? WHERE id=?", state, doc);
    catalog.update("UPDATE personal_imports SET state=?,error_code=?,updated_at=? WHERE id=?", state, error, Instant.now().toString(), task.get("id"));
  }

  private Path managed(String relative) throws Exception {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root) || Files.isSymbolicLink(resolved)) throw new Failure(404, "NOT_FOUND");
    return resolved;
  }
  void markDeleted(long documentId, String path) throws Exception {
    repository.inTransaction(() -> {
      catalog.update("UPDATE personal_documents SET deleted=1 WHERE document_id=?", documentId);
      Path source = Path.of(path).toAbsolutePath().normalize();
      if (source.startsWith(root)) {
        String relative = root.relativize(source).toString().replace('\\', '/');
        catalog.update("UPDATE personal_operations SET state='failed',error_code='DOCUMENT_DELETED' WHERE target_path=? AND state='pending'", relative);
      }
      return null;
    });
  }
  void markRestored(long documentId) throws Exception { catalog.update("UPDATE personal_documents SET deleted=0 WHERE document_id=?", documentId); }
  @Override public InputStream original(Caller caller, String id) throws Exception {
    synchronized (kb) {
      var row = reader.row(id, caller);
      if (row.get("original_path") == null) throw new Failure(404, "NOT_FOUND");
      return Files.newInputStream(managed((String) row.get("original_path")));
    }
  }
  @Override public void close() throws Exception {
    closed = true; worker.shutdownNow();
    if (!worker.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("personal worker did not stop");
  }
}
