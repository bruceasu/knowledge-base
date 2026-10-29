package me.asu.kb.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.asu.http.HTTPServer;
import me.asu.http.Request;
import me.asu.http.Response;
import me.asu.json.OKJSON;
import me.asu.kb.runtime.PersonalKnowledgeSettings;

/** Authentication before request-body parsing. Configuration faults fail closed. */
final class PersonalAccessGate implements HTTPServer.RequestGate {
  private final PersonalKnowledgeSettings settings;

  PersonalAccessGate(PersonalKnowledgeSettings settings) throws IOException {
    this.settings = settings;
    secret(settings.proxySecretFile());
    credentials();
  }

  @Override public boolean accept(Request request, Response response) throws IOException {
    response.getHeaders().replace("Cache-Control", "no-store");
    // Rejected and bounded bodies must never be drained without a limit for reuse.
    response.getHeaders().replace("Connection", "close");
    response.getHeaders().replace("X-Content-Type-Options", "nosniff");
    response.getHeaders().replace("Referrer-Policy", "no-referrer");
    response.getHeaders().replace("Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    String path = request.getPath();
    try {
      if (secretaryRoute(path, request.getMethod())) {
        String header = request.getHeaders().get("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 4096) {
          return deny(response, 401, "UNAUTHENTICATED");
        }
        String digest = sha256(header.substring(7));
        Credential principal = null;
        for (Credential credential : credentials()) {
          if (equal(digest, credential.tokenHash())) principal = credential;
        }
        if (principal == null) return deny(response, 401, "UNAUTHENTICATED");
        String scope = path.endsWith("/notes") || path.contains("/note-creations/")
            ? "knowledge:note:create" : "knowledge:read";
        if (!principal.scopes().contains(scope)) return deny(response, 403, "FORBIDDEN");
        request.useRawBody();
        request.getDataMap().put(PersonalAccessGate.class.getName(), principal);
        return true;
      }
      String proxy = request.getHeaders().get("X-KB-Proxy-Secret");
      String user = request.getHeaders().get("Remote-User");
      if (!request.getSocket().getInetAddress().isLoopbackAddress()
          || proxy == null || !equal(proxy, secret(settings.proxySecretFile()))
          || user == null || user.isBlank() || user.length() > 200 || user.contains(",")) {
        return deny(response, 401, "UNAUTHENTICATED");
      }
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
        if (!settings.publicBaseUrl().toString().equals(request.getHeaders().get("Origin"))
            || !"1".equals(request.getHeaders().get("X-KB-CSRF"))) {
          return deny(response, 403, "FORBIDDEN");
        }
      }
      if (path.startsWith("/api/v2/personal/")
          || Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
        request.useRawBody();
      } else if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
        if (!request.isJson()) return deny(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        // Reading waits until preprocess has handled Expect: 100-continue.
        request.limitBody(262144);
      }
      return true;
    } catch (IllegalArgumentException | IOException failure) {
      return deny(response, 503, "UNAVAILABLE");
    }
  }

  static boolean secretaryRoute(String path, String method) {
    return (method.equals("GET") && (path.equals("/api/v2/secretary/capabilities")
        || path.matches("/api/v2/secretary/documents/[A-Za-z0-9_-]+")
        || path.matches("/api/v2/secretary/note-creations/[A-Za-z0-9_-]+")))
        || (method.equals("POST") && (path.equals("/api/v2/secretary/search")
        || path.equals("/api/v2/secretary/notes")));
  }

  static Credential principal(Request request) {
    Object principal = request.getDataMap().get(PersonalAccessGate.class.getName());
    if (!(principal instanceof Credential credential)) throw new IllegalStateException("authentication required");
    return credential;
  }

  private List<Credential> credentials() throws IOException {
    String json = boundedFile(settings.credentialsFile(), 65536);
    Object parsed = OKJSON.toJson(json, Map.class, OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE);
    if (!(parsed instanceof Map<?, ?> map) || map.size() != 1
        || !(map.get("credentials") instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
      throw new IllegalArgumentException("invalid credential configuration");
    }
    var result = new java.util.ArrayList<Credential>();
    var hashes = new java.util.HashSet<String>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> values) || values.size() != 4
          || !(values.get("subject") instanceof String subject)
          || !subject.matches("[A-Za-z0-9_-]{1,100}")
          || subject.equals("browser")
          || !(values.get("tokenSha256") instanceof String hash)
          || !hash.matches("[0-9a-f]{64}") || !hashes.add(hash)) {
        throw new IllegalArgumentException("invalid credential configuration");
      }
      Set<String> scopes = strings(values.get("scopes"));
      Set<String> collections = strings(values.get("collections"));
      if (scopes.isEmpty() || !Set.of("knowledge:read", "knowledge:note:create").containsAll(scopes)
          || collections.isEmpty()) throw new IllegalArgumentException("invalid credential permissions");
      result.add(new Credential(subject, hash, scopes, collections));
    }
    return List.copyOf(result);
  }

  private static Set<String> strings(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 100) throw new IllegalArgumentException("invalid permissions");
    var values = new java.util.HashSet<String>();
    for (Object item : list) {
      if (!(item instanceof String text) || !text.matches("[A-Za-z0-9_:*\\-]{1,100}") || !values.add(text)) {
        throw new IllegalArgumentException("invalid permissions");
      }
    }
    return Set.copyOf(values);
  }

  private static String secret(Path path) throws IOException {
    String secret = boundedFile(path, 1024).strip();
    if (!secret.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("invalid proxy secret");
    return secret;
  }

  private static String boundedFile(Path path, int limit) throws IOException {
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) throw new IOException("configuration exceeds limit");
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static boolean equal(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private static boolean deny(Response response, int status, String code) throws IOException {
    String requestId = "req_" + UUID.randomUUID();
    response.getHeaders().replace("X-Request-Id", requestId);
    response.getHeaders().replace("Connection", "close");
    if (status == 401) response.getHeaders().replace("WWW-Authenticate", "Bearer");
    byte[] bytes = ("{\"apiVersion\":\"1.0\",\"requestId\":\"" + requestId
        + "\",\"error\":{\"code\":\"" + code
        + "\",\"message\":\"Request rejected\",\"retryable\":" + (status == 503) + ",\"details\":{}}}")
        .getBytes(StandardCharsets.UTF_8);
    response.sendHeaders(status, bytes.length, -1, null, "application/json; charset=utf-8", null);
    response.getBody().write(bytes);
    return false;
  }

  record Credential(String subject, String tokenHash, Set<String> scopes, Set<String> collections) {
    @Override public String toString() { return "Credential[redacted]"; }
  }
}
