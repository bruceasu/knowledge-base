package me.asu.kb.service;

import static me.asu.kb.service.PersonalValues.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.asu.kb.runtime.PersonalKnowledgeApplication.Caller;
import me.asu.kb.runtime.PersonalKnowledgeApplication.Failure;

/** Read model shared by secretary queries, citations and browser reading. Caller holds KB lock. */
final class PersonalReadService {
  private final KnowledgeBase kb;
  private final KnowledgeRepository repository;
  private final PersonalCatalog catalog;
  private final LuceneSearchIndex index;
  private final String origin;
  PersonalReadService(KnowledgeBase kb, KnowledgeRepository repository, PersonalCatalog catalog,
      LuceneSearchIndex index, String origin) {
    this.kb = kb; this.repository = repository; this.catalog = catalog; this.index = index; this.origin = origin;
  }

  void mapExisting() throws Exception {
    for (var row : catalog.query("SELECT d.id,d.file_type,d.path FROM documents d LEFT JOIN personal_documents p ON p.document_id=d.id WHERE p.id IS NULL")) {
      interrupted();
      if (String.valueOf(row.get("path")).replace('\\', '/').contains("/.kb/personal/")) continue;
      String now = Instant.now().toString();
      catalog.update("INSERT INTO personal_documents(id,document_id,collection_id,format,source_kind,extraction_state,created_at,updated_at) VALUES(?,?,'default',?,'external_reference','ready',?,?)",
          "doc_" + UUID.randomUUID(), row.get("id"), format(String.valueOf(row.get("file_type"))), now, now);
    }
  }

  Map<String, Object> row(String id, Caller caller) throws Exception {
    var rows = catalog.query("SELECT * FROM personal_documents WHERE id=? AND deleted=0", id);
    if (rows.isEmpty() || !caller.allows((String) rows.get(0).get("collection_id"))) throw new Failure(404, "NOT_FOUND");
    var row = rows.get(0);
    if (row.get("document_id") != null) {
      long internal = ((Number) row.get("document_id")).longValue();
      if (repository.findDocumentById(internal).isEmpty() || !repository.isDocumentInActiveRoot(internal)
          || repository.findArticleById(internal).map(a -> !a.state().name().equals("ACTIVE")).orElse(true)) {
        throw new Failure(404, "NOT_FOUND");
      }
    }
    return row;
  }

  Map<String, Object> metadata(Map<String, Object> row) throws Exception {
    if (row.get("document_id") == null) throw new Failure(409, "CONTENT_NOT_READY");
    long internal = ((Number) row.get("document_id")).longValue();
    var document = repository.findDocumentById(internal).orElseThrow(() -> new Failure(404, "NOT_FOUND"));
    var article = repository.findArticleById(internal).orElseThrow(() -> new Failure(404, "NOT_FOUND"));
    var metadata = repository.findEffectiveDocumentMetadata(internal);
    String title = metadata.title().map(KnowledgeRepository.EffectiveMetadataValue::value).orElse("资料");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("documentId", row.get("id"));
    item.put("revision", "rev_" + article.currentRevision().revisionNumber() + "_" + document.hash());
    item.put("contentHash", "sha256:" + document.hash());
    item.put("title", title); item.put("format", row.get("format"));
    item.put("collectionIds", List.of(row.get("collection_id")));
    item.put("tags", metadata.tags().stream().map(KnowledgeRepository.EffectiveMetadataValue::value).toList());
    item.put("updatedAt", article.currentRevision().createdAt().toString());
    item.put("source", Map.of("kind", row.get("source_kind"), "displayName", title,
        "url", origin + "/webui/documents/" + row.get("id")));
    item.put("freshness", article.projection().state().name().equals("INDEXED")
        ? ("external_reference".equals(row.get("source_kind")) ? "unknown" : "current") : "stale");
    return item;
  }

  Map<String, Object> search(Caller caller, Map<String, Object> request) throws Exception {
    fields(request, "query", "limit", "excerptChars", "filters");
    if (!(request.get("query") instanceof String rawQuery)) throw invalid();
    String query = text(Map.of("query", rawQuery.strip()), "query", 1, 2000);
    if (query.isBlank()) throw invalid();
    int limit = integer(request, "limit", 8, 1, 20);
    int excerpt = integer(request, "excerptChars", 1200, 200, 2000);
    Map<String, Object> filter = request.containsKey("filters") ? map(request.get("filters")) : Map.of();
    fields(filter, "collectionIds", "tags", "formats", "updatedFrom", "updatedBefore");
    var collections = strings(filter, "collectionIds", 20, 100);
    var tags = strings(filter, "tags", 20, 100);
    var formats = strings(filter, "formats", 7, 20);
    if (!Set.of("markdown", "text", "html", "pdf", "docx", "image", "other").containsAll(formats)) throw invalid();
    for (String collection : collections) {
      if (!caller.allows(collection) || catalog.query("SELECT id FROM personal_collections WHERE id=?", collection).isEmpty()) throw new Failure(404, "NOT_FOUND");
    }
    Instant from = date(filter.get("updatedFrom")), before = date(filter.get("updatedBefore"));
    if (from != null && before != null && !from.isBefore(before)) throw invalid();
    mapExisting();
    var allowed = new LinkedHashMap<Long, Map<String, Object>>();
    int staleCount = 0;
    for (var candidate : catalog.query("SELECT * FROM personal_documents WHERE deleted=0 AND document_id IS NOT NULL")) {
      interrupted();
      if (!caller.allows((String) candidate.get("collection_id"))
          || (!collections.isEmpty() && !collections.contains(candidate.get("collection_id")))
          || (!formats.isEmpty() && !formats.contains(candidate.get("format")))) continue;
      try {
        row((String) candidate.get("id"), caller);
        var item = metadata(candidate);
        var date = Instant.parse((String) item.get("updatedAt"));
        if (from != null && date.isBefore(from) || before != null && !date.isBefore(before)) continue;
        if (!tags.isEmpty() && ((List<?>) item.get("tags")).stream().noneMatch(tags::contains)) continue;
        allowed.put(((Number) candidate.get("document_id")).longValue(), item);
        if ("stale".equals(item.get("freshness"))) staleCount++;
      } catch (Failure missing) { if (missing.status() != 404) throw missing; }
    }
    // Permissions and all filters are applied inside Lucene before top-k truncation.
    var hits = index.search(query, 5001, null, List.of(), allowed.keySet());
    if (!allowed.isEmpty() && staleCount == allowed.size() && hits.isEmpty()) {
      throw new Failure(503, "UNAVAILABLE");
    }
    var seen = new java.util.HashSet<Long>();
    var results = new ArrayList<Map<String, Object>>();
    boolean more = false;
    for (var hit : hits) {
      if (!seen.add(hit.documentId())) continue;
      if (results.size() == limit) { more = true; break; }
      var item = new LinkedHashMap<>(allowed.get(hit.documentId()));
      item.put("excerpt", slice(hit.snippet(), 0, excerpt)); item.put("location", location());
      results.add(item);
    }
    var diagnostics = new ArrayList<Map<String, String>>();
    if (staleCount > 0) diagnostics.add(Map.of("code", "PARTIAL_INDEX", "message", "部分可见资料的索引尚未就绪，结果可能遗漏"));
    if (hits.size() >= 5001) diagnostics.add(Map.of("code", "PARTIAL_INDEX", "message", "候选预算已用尽，请缩小查询"));
    boolean complete = diagnostics.isEmpty();
    return Map.of("items", results, "hasMore", more, "complete", complete,
        "diagnostics", diagnostics);
  }

  Map<String, Object> read(Caller caller, Map<String, Object> request) throws Exception {
    fields(request, "documentId", "revision", "maxChars", "cursor");
    String id = text(request, "documentId", 1, 100), revision = text(request, "revision", 1, 200);
    int maximum = integer(request, "maxChars", 12000, 1000, 20000);
    var row = row(id, caller);
    if ("unsupported".equals(row.get("extraction_state"))) throw new Failure(422, "UNSUPPORTED_CONTENT");
    var result = metadata(row);
    if (!revision.equals(result.get("revision"))) throw new Failure(409, "REVISION_CHANGED");
    int offset = request.containsKey("cursor") ? offset(text(request, "cursor", 1, 2048), caller, id, revision) : 0;
    long internal = ((Number) row.get("document_id")).longValue();
    var document = repository.findDocumentById(internal).orElseThrow(() -> new Failure(404, "NOT_FOUND"));
    if (Files.size(Path.of(document.path())) > 100_000_000) throw new Failure(422, "UNSUPPORTED_CONTENT");
    byte[] snapshot;
    try (var input = Files.newInputStream(Path.of(document.path()))) {
      var output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int n; (n = input.read(buffer)) != -1;) {
        interrupted();
        if ((long) output.size() + n > 100_000_000) throw new Failure(422, "UNSUPPORTED_CONTENT");
        output.write(buffer, 0, n);
      }
      snapshot = output.toByteArray();
    }
    var digest = java.security.MessageDigest.getInstance("SHA-256").digest(snapshot);
    if (!java.util.HexFormat.of().formatHex(digest).equals(document.hash())) throw new Failure(409, "REVISION_CHANGED");
    String content = kb.decode(snapshot);
    int count = content.codePointCount(0, content.length());
    result.put("content", slice(content, offset, maximum));
    result.put("contentFormat", "markdown".equals(row.get("format")) ? "markdown" : "text");
    result.put("location", location());
    boolean truncated = offset + maximum < count;
    result.put("truncated", truncated);
    result.put("nextCursor", truncated ? cursor(caller, id, revision, offset + maximum) : null);
    result.put("diagnostics", List.of());
    return result;
  }

  private String cursor(Caller caller, String id, String revision, int offset) throws Exception {
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json(Map.of(
        "subject", caller.subject(), "id", id, "revision", revision, "offset", offset,
        "expires", Instant.now().plusSeconds(900).getEpochSecond())).getBytes(StandardCharsets.UTF_8));
    return payload + "." + sign(payload);
  }
  private int offset(String cursor, Caller caller, String id, String revision) throws Exception {
    try {
      String[] pieces = cursor.split("\\.", -1);
      if (pieces.length != 2 || !java.security.MessageDigest.isEqual(sign(pieces[0]).getBytes(StandardCharsets.US_ASCII), pieces[1].getBytes(StandardCharsets.US_ASCII))) throw invalid();
      var value = parse(new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8));
      if (!caller.subject().equals(value.get("subject")) || !id.equals(value.get("id")) || !revision.equals(value.get("revision"))) throw invalid();
      if (((Number) value.get("expires")).longValue() <= Instant.now().getEpochSecond()) throw new Failure(410, "CURSOR_EXPIRED");
      return integer(value, "offset", 0, 0, Integer.MAX_VALUE);
    } catch (Failure failure) {
      if (failure.status() == 410) throw failure;
      throw new Failure(400, "INVALID_CURSOR");
    } catch (RuntimeException invalid) { throw new Failure(400, "INVALID_CURSOR"); }
  }
  private String sign(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(catalog.cursorKey(), "HmacSHA256"));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
  }
  private static Instant date(Object value) {
    if (value == null) return null;
    try { return Instant.parse((String) value); } catch (RuntimeException invalid) { throw invalid(); }
  }
  private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new Failure(503, "UNAVAILABLE"); }
  static String format(String extension) {
    return switch (extension.toLowerCase(java.util.Locale.ROOT)) {
      case "md", "markdown" -> "markdown";
      case "txt", "text", "org" -> "text";
      case "htm", "html" -> "html";
      case "pdf", "docx" -> extension;
      case "png", "jpg", "jpeg", "gif", "webp" -> "image";
      default -> "other";
    };
  }
}
