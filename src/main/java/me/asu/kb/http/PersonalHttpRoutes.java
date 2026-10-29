package me.asu.kb.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.asu.http.Application;
import me.asu.http.Headers;
import me.asu.http.MultipartInputStream;
import me.asu.http.Request;
import me.asu.http.Response;
import me.asu.json.OKJSON;
import me.asu.kb.runtime.PersonalKnowledgeApplication;
import me.asu.kb.runtime.PersonalKnowledgeApplication.*;
import me.asu.kb.runtime.SingleWorkspaceApplication;

/** Versioned transport adapter; has no database or filesystem access. */
final class PersonalHttpRoutes {
  private static final int JSON_OPTIONS = OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE | OKJSON.OPTIONS_NULLABLE;
  private static final Caller BROWSER = new Caller("browser", Set.of("knowledge:read", "knowledge:note:create"), Set.of("*"));
  private final Application http;
  private final SingleWorkspaceApplication application;
  PersonalHttpRoutes(Application http, SingleWorkspaceApplication application) { this.http = http; this.application = application; }

  void register() {
    add("/api/v2/secretary/capabilities", "GET", Operation.CAPABILITIES, false);
    add("/api/v2/secretary/search", "POST", Operation.SEARCH, false);
    add("/api/v2/secretary/documents/{id}", "GET", Operation.READ, false);
    add("/api/v2/secretary/notes", "POST", Operation.CREATE_NOTE, false);
    add("/api/v2/secretary/note-creations/{key}", "GET", Operation.CREATION_STATUS, false);
    add("/api/v2/personal/search", "POST", Operation.SEARCH, true);
    add("/api/v2/personal/documents", "GET", Operation.RECENT, true);
    add("/api/v2/personal/documents/{id}", "GET", Operation.BROWSER_READ, true);
    add("/api/v2/personal/notes", "POST", Operation.CREATE_NOTE, true);
    add("/api/v2/personal/note-creations/{key}", "GET", Operation.CREATION_STATUS, true);
    add("/api/v2/personal/imports", "GET", Operation.LIST_IMPORTS, true);
    add("/api/v2/personal/imports/{id}/retry", "POST", Operation.RETRY_IMPORT, true);
    http.addRoute("/api/v2/personal/uploads", this::upload, "POST");
    http.addRoute("/api/v2/personal/documents/{id}/original", this::original, "GET");
    http.addRoute("/webui/documents/{id}", this::readingPage, "GET");
  }

  private void add(String path, String method, Operation operation, boolean browser) {
    http.addRoute(path, (request, response) -> respond(response, () -> {
      rejectObsolete(request);
      Caller caller = browser ? BROWSER : caller(request);
      Map<String, Object> values = method.equals("POST") ? jsonBody(request) : query(request);
      if (method.equals("POST") && request.getUri().getRawQuery() != null) throw new Failure(400, "INVALID_ARGUMENT");
      if (operation == Operation.CREATE_NOTE) {
        if (values.containsKey("key")) throw new Failure(400, "INVALID_ARGUMENT");
        values.put("key", request.getHeaders().get("Idempotency-Key"));
      } else if (operation == Operation.READ || operation == Operation.BROWSER_READ) {
        if (!Set.of("revision", "maxChars", "cursor").containsAll(values.keySet())) throw new Failure(400, "INVALID_ARGUMENT");
        values.put("documentId", request.getParameter("id"));
        if (values.containsKey("maxChars")) {
          try { values.put("maxChars", Integer.parseInt((String) values.get("maxChars"))); }
          catch (RuntimeException invalid) { throw new Failure(400, "INVALID_ARGUMENT"); }
        }
      } else if (operation == Operation.CREATION_STATUS) {
        if (!values.isEmpty()) throw new Failure(400, "INVALID_ARGUMENT");
        values.put("key", request.getParameter("key"));
      } else if (operation == Operation.RETRY_IMPORT) {
        if (!values.isEmpty()) throw new Failure(400, "INVALID_ARGUMENT");
        values.put("id", request.getParameter("id"));
      }
      return application.operationCoordinator().execute(
          () -> application.documents().personal().execute(operation, caller, values), Duration.ofSeconds(10));
    }), method);
  }

  private int upload(Request request, Response response) throws IOException {
    return respond(response, () -> {
      rejectObsolete(request);
      if (!query(request).isEmpty()) throw new Failure(400, "INVALID_ARGUMENT");
      Map<String, String> type = request.getHeaders().getParams("Content-Type");
      if (!type.containsKey("multipart/form-data") || !type.containsKey("boundary")) throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
      var stream = new MultipartInputStream(new BoundedInput(request.getBody(), 100_065_536L), type.get("boundary").getBytes(StandardCharsets.US_ASCII));
      if (!stream.nextPart()) throw new Failure(400, "INVALID_ARGUMENT");
      Headers headers = Headers.readHeaders(stream);
      var disposition = headers.getParams("Content-Disposition");
      if (!"file".equals(disposition.get("name")) || !disposition.containsKey("filename")) throw new Failure(400, "INVALID_ARGUMENT");
      InputStream oneFile = new FilterInputStream(stream) {
        private boolean checked;
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          int count = in.read(bytes, offset, length);
          if (count == -1 && !checked) {
            checked = true;
            if (stream.nextPart()) throw new Failure(400, "MULTIPLE_FILES_NOT_ALLOWED");
          }
          return count;
        }
      };
      // Stream directly to the application's managed spool; no transport temp path.
      return application.documents().personal().upload(BROWSER, disposition.get("filename"), oneFile);
    });
  }

  private int original(Request request, Response response) throws IOException {
    try {
      rejectObsolete(request);
      if (!query(request).isEmpty()) throw new Failure(400, "INVALID_ARGUMENT");
      try (InputStream input = application.documents().personal().original(BROWSER, request.getParameter("id"))) {
        response.getHeaders().replace("Content-Disposition", "attachment; filename=original");
        response.sendHeaders(200, -1, -1, null, "application/octet-stream", null);
        input.transferTo(response.getBody());
      }
      return 0;
    } catch (Exception failure) { if (response.headersSent()) throw new IOException("download interrupted"); return error(response, failure); }
  }

  private int readingPage(Request request, Response response) throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/webui/personal.html")) {
      if (input == null) return 404;
      byte[] bytes = input.readAllBytes();
      response.sendHeaders(200, bytes.length, -1, null, "text/html; charset=utf-8", null);
      response.getBody().write(bytes);
      return 0;
    }
  }

  private static Caller caller(Request request) {
    var credential = PersonalAccessGate.principal(request);
    return new Caller(credential.subject(), credential.scopes(), credential.collections());
  }
  private static Map<String, Object> query(Request request) {
    var result = new LinkedHashMap<String, Object>();
    for (String[] pair : Request.parseParameters(request.getUri().getRawQuery())) {
      if (result.putIfAbsent(pair[0], pair[1]) != null) throw new Failure(400, "INVALID_ARGUMENT");
    }
    return result;
  }
  @SuppressWarnings("unchecked") private static Map<String, Object> jsonBody(Request request) throws IOException {
    String contentType = request.contentType();
    if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] bytes = request.getBody().readNBytes(262145);
    if (bytes.length > 262144) throw new Failure(413, "PAYLOAD_TOO_LARGE");
    try {
      String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      if (!text.stripLeading().startsWith("{")) throw new Failure(400, "INVALID_ARGUMENT");
      Object value = OKJSON.toJson(text, Map.class, JSON_OPTIONS);
      if (!(value instanceof Map<?, ?> map)) throw new Failure(400, "INVALID_ARGUMENT");
      return new LinkedHashMap<>((Map<String, Object>) map);
    } catch (RuntimeException | java.nio.charset.CharacterCodingException invalid) { throw new Failure(400, "INVALID_ARGUMENT"); }
  }
  private static void rejectObsolete(Request request) {
    if (request.getHeaders().contains("X-Workspace") || request.getHeaders().contains("X-Workspace-Id")) throw new Failure(400, "OBSOLETE_WORKSPACE_FIELD");
  }
  private int respond(Response response, Action action) throws IOException {
    try {
      var result = action.execute();
      return send(response, result.status(), "data", result.data());
    } catch (Exception failure) { return error(response, failure); }
  }
  private static int error(Response response, Exception failure) throws IOException {
    Throwable cause = failure;
    while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) cause = cause.getCause();
    int status = cause instanceof Failure known ? known.status() : cause instanceof java.util.concurrent.TimeoutException ? 504
        : cause instanceof java.util.concurrent.RejectedExecutionException || cause instanceof InterruptedException ? 503 : 500;
    String code = cause instanceof Failure known ? known.code() : status == 504 ? "DEADLINE_EXCEEDED" : status == 503 ? "UNAVAILABLE" : "INTERNAL";
    if (status == 429) response.getHeaders().replace("Retry-After", "10");
    if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
    return send(response, status, "error", Map.of("code", code, "message", "请求未完成", "retryable", status == 429 || status >= 500, "details", Map.of()));
  }
  private static int send(Response response, int status, String key, Object value) throws IOException {
    String id = "req_" + UUID.randomUUID();
    byte[] bytes = OKJSON.stringify(Map.of("apiVersion", "1.0", "requestId", id, key, value), JSON_OPTIONS).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 1_048_576) {
      status = 503;
      bytes = OKJSON.stringify(Map.of("apiVersion", "1.0", "requestId", id, "error", Map.of("code", "RESPONSE_BUDGET_EXCEEDED", "message", "请缩小请求范围", "retryable", false, "details", Map.of())), JSON_OPTIONS).getBytes(StandardCharsets.UTF_8);
    }
    response.getHeaders().replace("X-Request-Id", id);
    response.sendHeaders(status, bytes.length, -1, null, "application/json; charset=utf-8", null);
    response.getBody().write(bytes);
    return 0;
  }
  private static final class BoundedInput extends FilterInputStream {
    private final long limit; private long count;
    BoundedInput(InputStream input, long limit) { super(input); this.limit = limit; }
    @Override public int read() throws IOException { int value = in.read(); if (value != -1 && ++count > limit) throw new Failure(413, "PAYLOAD_TOO_LARGE"); return value; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException { int n = in.read(bytes, offset, length); if (n > 0 && (count += n) > limit) throw new Failure(413, "PAYLOAD_TOO_LARGE"); return n; }
  }
  @FunctionalInterface private interface Action { Result execute() throws Exception; }
}
