package me.asu.kb.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.asu.json.OKJSON;
import me.asu.kb.adapter.codesearch.RipgrepCodeSearchAdapter;
import me.asu.kb.adapter.document.LegacyDocumentSnapshot;
import me.asu.kb.adapter.document.LegacySourceProvenance;
import me.asu.kb.adapter.gitevidence.GitEvidenceSettings;
import me.asu.kb.adapter.symbol.CtagsSymbolExtractor;
import me.asu.kb.ingestion.DocumentIngestionUnit;
import me.asu.kb.ingestion.IngestionRequest;
import me.asu.kb.ingestion.IngestionReport;
import me.asu.kb.runtime.ServiceLifecycleState;
import me.asu.kb.runtime.SingleWorkspaceLauncher;
import me.asu.kb.runtime.SingleWorkspaceDocumentApplication;
import me.asu.kb.runtime.SingleWorkspaceKnowledgeService;
import me.asu.kb.runtime.SingleWorkspaceServiceConfig;
import me.asu.kb.service.Article;
import me.asu.kb.service.ArticleId;
import me.asu.kb.service.ArticleMetadata;
import me.asu.kb.service.ArticleRevision;
import me.asu.kb.service.ArticleSourceKind;
import me.asu.kb.service.ArticleState;
import me.asu.kb.service.ProjectionState;
import me.asu.kb.service.ProjectionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleWorkspaceHttpIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
  private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

  @TempDir Path tempDirectory;

  @Test
  void servesFreshSharedWorkspaceAndShutsDownDeterministically() throws Exception {
    Path root = tempDirectory.resolve("workspace");
    AtomicInteger documentCloseCount = new AtomicInteger();
    SingleWorkspaceKnowledgeService service = openService(root, documentCloseCount);
    int port = freePort();
    SingleWorkspaceHttpApp app = new SingleWorkspaceHttpApp(service, port);
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    try {
      app.start();
      waitForHealth(client, port);

      HttpResponse<String> ready = get(client, port, "/api/v2/ready");
      assertEquals(200, ready.statusCode());
      assertTrue(ready.body().contains("\"ready\":true"));
      assertFalse(ready.body().contains(root.toString()));

      HttpResponse<String> webUi = get(client, port, "/webui/index.html");
      assertEquals(200, webUi.statusCode());
      assertTrue(webUi.body().contains("src=\"./app.js\""));
      HttpResponse<String> webUiScript = get(client, port, "/webui/app.js");
      assertEquals(200, webUiScript.statusCode());
      assertTrue(webUiScript.body().contains("/api/v2/knowledge/search"));
      assertFalse(webUiScript.body().contains("/api/v1"));
      assertFalse(webUiScript.body().contains("workspaceId"));

      HttpResponse<String> obsolete =
          client.send(
              HttpRequest.newBuilder(uri(port, "/api/v2/status"))
                  .header("X-Workspace-Id", "legacy")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(400, obsolete.statusCode());
      assertTrue(obsolete.body().contains("OBSOLETE_WORKSPACE_FIELD"));

      IngestionReport ingestion =
          service
          .projectionIngestion()
          .ingest(
              IngestionRequest.of(
                  SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                  List.of(documentUnit())));
      assertTrue(ingestion.fullySuccessful(), ingestion.toString());
      String objectId = "document:v1:sha256:" + sha256("doc:http-integration");

      HttpResponse<String> search =
          get(client, port, "/api/v2/knowledge/search?query=deterministic&limit=5");
      assertEquals(200, search.statusCode());
      assertTrue(search.body().contains(objectId), search.body());
      assertFalse(search.body().contains("workspaceId"));

      HttpResponse<String> read =
          get(client, port, "/api/v2/knowledge/objects/" + encode(objectId));
      assertEquals(200, read.statusCode());
      assertTrue(read.body().contains("Deterministic HTTP Service"));

      HttpResponse<String> trace =
          get(client, port, "/api/v2/knowledge/trace/" + encode(objectId));
      assertEquals(200, trace.statusCode());
      assertTrue(trace.body().contains(objectId));

      HttpResponse<String> context =
          postJson(
              client,
              port,
              "/api/v2/knowledge/context",
              Map.of("target", "deterministic", "intent", "UNDERSTAND", "tokenBudget", 512));
      assertEquals(200, context.statusCode());
      assertTrue(context.body().contains(objectId));

      HttpResponse<String> missing =
          get(client, port, "/api/v2/knowledge/objects/missing-object");
      assertEquals(404, missing.statusCode());
    } finally {
      app.close();
      app.close();
    }

    assertEquals(1, documentCloseCount.get());
    assertEquals(ServiceLifecycleState.CLOSED, service.readiness().state());
    HttpClient newClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    assertThrows(IOException.class, () -> get(newClient, port, "/api/v2/health"));
  }

  @Test
  void rejectsObsoleteConfigurationBeforeCreatingFreshState() {
    Path root = tempDirectory.resolve("uncreated-workspace");

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SingleWorkspaceServiceConfig.parse(
                    new String[] {
                      "--workspace-root", root.toString(), "--server-workspace", "legacy"
                    },
                    Map.of()));

    assertTrue(failure.getMessage().contains("--server-workspace"));
    assertFalse(java.nio.file.Files.exists(root));
  }

  @Test
  void documentRoutesPreserveEditingConflictHistoryAndBoundedListing() throws Exception {
    Path root = tempDirectory.resolve("document-workspace");
    SingleWorkspaceKnowledgeService service = openProductionService(root);
    int port = freePort();
    SingleWorkspaceHttpApp app = new SingleWorkspaceHttpApp(service, port);
    HttpClient client = HttpClient.newHttpClient();
    try {
      app.start();
      waitForHealth(client, port);

      HttpResponse<String> created =
          postJson(
              client,
              port,
              "/api/v2/documents",
              Map.of(
                  "filename", "http.md",
                  "content", "# HTTP\n\nfirst",
                  "title", "HTTP Article",
                  "tags", List.of("transport")));
      assertEquals(200, created.statusCode());
      long documentId = extractId(created.body());

      HttpResponse<String> view = get(client, port, "/api/v2/documents/" + documentId);
      assertEquals(200, view.statusCode());
      assertTrue(view.body().contains("# HTTP"), view.body());
      assertTrue(view.body().contains("HTTP Article"), view.body());
      assertTrue(view.body().contains("currentRevision"), view.body());

      HttpResponse<String> conflict =
          patchJson(
              client,
              port,
              "/api/v2/documents/" + documentId,
              Map.of("content", "# HTTP\n\nsecond", "expectedBaseHash", "invalid-base"));
      assertEquals(409, conflict.statusCode());
      assertTrue(conflict.body().contains("DOCUMENT_CONFLICT"), conflict.body());

      HttpResponse<String> metadata =
          patchJson(
              client,
              port,
              "/api/v2/documents/" + documentId,
              Map.of("content", "# HTTP\n\nfirst", "title", "Updated HTTP"));
      assertEquals(200, metadata.statusCode());
      assertTrue(metadata.body().contains("Updated HTTP"), metadata.body());

      HttpResponse<String> history =
          get(client, port, "/api/v2/documents/" + documentId + "/revisions");
      assertEquals(200, history.statusCode());
      assertTrue(history.body().contains("document-revisions"), history.body());

      HttpResponse<String> page =
          get(client, port, "/api/v2/documents?limit=1&offset=0&query=HTTP");
      assertEquals(200, page.statusCode());
      assertTrue(page.body().contains("\"limit\":1"), page.body());
      assertTrue(page.body().contains("\"total\":1"), page.body());
    } finally {
      app.close();
    }
  }

  @Test
  void sessionRoutesPreserveCreateListMessageSummaryExportAndImport() throws Exception {
    Path root = tempDirectory.resolve("session-workspace");
    SingleWorkspaceKnowledgeService service = openProductionService(root);
    int port = freePort();
    SingleWorkspaceHttpApp app = new SingleWorkspaceHttpApp(service, port);
    HttpClient client = HttpClient.newHttpClient();
    try {
      app.start();
      waitForHealth(client, port);

      HttpResponse<String> created =
          postJson(client, port, "/api/v2/sessions", Map.of("title", "Daily"));
      assertEquals(200, created.statusCode());
      long sessionId = extractId(created.body());

      HttpResponse<String> listed = get(client, port, "/api/v2/sessions");
      assertEquals(200, listed.statusCode());
      assertTrue(listed.body().contains("Daily"), listed.body());

      HttpResponse<String> message =
          postJson(
              client,
              port,
              "/api/v2/sessions/" + sessionId + "/messages",
              Map.of("role", "user", "content", "What changed today?"));
      assertEquals(200, message.statusCode());

      HttpResponse<String> summary =
          postJson(
              client,
              port,
              "/api/v2/sessions/" + sessionId + "/summary",
              Map.of("summaryText", "Daily notes"));
      assertEquals(200, summary.statusCode());

      HttpResponse<String> exported =
          get(client, port, "/api/v2/sessions/" + sessionId + "/export");
      assertEquals(200, exported.statusCode());
      assertTrue(exported.body().contains("What changed today?"), exported.body());
      assertTrue(exported.body().contains("Daily notes"), exported.body());

      HttpResponse<String> imported =
          postRawJson(client, port, "/api/v2/sessions/import", exported.body());
      assertEquals(200, imported.statusCode());

      HttpResponse<String> relisted = get(client, port, "/api/v2/sessions");
      assertEquals(200, relisted.statusCode());
      assertTrue(countOccurrences(relisted.body(), "Daily") >= 2, relisted.body());
    } finally {
      app.close();
    }
  }

  private SingleWorkspaceKnowledgeService openProductionService(Path root) throws Exception {
    return SingleWorkspaceLauncher.open(
        SingleWorkspaceLauncher.load(
            new String[] {"--workspace-root", root.toString(), "--llm-provider", "echo"}));
  }

  private SingleWorkspaceKnowledgeService openService(Path root, AtomicInteger closeCount)
      throws Exception {
    SingleWorkspaceServiceConfig config =
        SingleWorkspaceServiceConfig.parse(
            new String[] {"--workspace-root", root.toString()}, Map.of());
    Path unavailableCtags = tempDirectory.resolve("tools/ctags.exe").toAbsolutePath();
    Path unavailableGit = tempDirectory.resolve("tools/git.exe").toAbsolutePath();
    var profiles =
        new SingleWorkspaceKnowledgeService.ToolProfiles(
            RipgrepCodeSearchAdapter.Settings.defaults(),
            CtagsSymbolExtractor.Settings.defaults(unavailableCtags.toString()),
            GitEvidenceSettings.defaults(unavailableGit.toString()));
    return SingleWorkspaceKnowledgeService.open(
        config,
        ignored ->
            new SingleWorkspaceDocumentApplication() {
              @Override
              public Object execute(Operation operation, Map<String, Object> arguments) {
                return Map.of("ok", true, "operation", operation.name());
              }

              @Override
              public void close() {
                closeCount.incrementAndGet();
              }
            },
        profiles);
  }

  private static DocumentIngestionUnit documentUnit() {
    ArticleRevision revision =
        new ArticleRevision(
            1,
            "document-hash",
            "markdown",
            "docs/http-service.md",
            ArticleSourceKind.ROOT,
            NOW);
    Article article =
        new Article(
            new ArticleId(1),
            revision,
            new ArticleMetadata(
                Optional.of("Deterministic HTTP Service"),
                Optional.empty(),
                List.of(),
                List.of()),
            ArticleState.ACTIVE,
            new ProjectionStatus(ProjectionState.INDEXED, 1, Optional.empty(), NOW));
    return new DocumentIngestionUnit(
        new LegacyDocumentSnapshot(
            "doc:http-integration",
            article,
            "# Deterministic HTTP Service\nShared ingestion and retrieval state.",
            Optional.of(
                new LegacySourceProvenance(
                    Optional.of("workspace-file"),
                    Optional.of("docs/http-service.md"),
                    Optional.of("revision-1"))),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("docs/http-service.md")));
  }

  private static HttpResponse<String> get(HttpClient client, int port, String path)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> postJson(
      HttpClient client, int port, String path, Map<String, Object> body)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(uri(port, path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(OKJSON.stringify(body, 0)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> postRawJson(
      HttpClient client, int port, String path, String body)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(uri(port, path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> patchJson(
      HttpClient client, int port, String path, Map<String, Object> body)
      throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(uri(port, path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(OKJSON.stringify(body, 0)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static long extractId(String body) {
    Matcher matcher = ID_PATTERN.matcher(body);
    if (matcher.find()) {
      return Long.parseLong(matcher.group(1));
    }
    throw new IllegalArgumentException("id not found in response: " + body);
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }

  private static URI uri(int port, String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0));
      return socket.getLocalPort();
    }
  }

  private static void waitForHealth(HttpClient client, int port) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    IOException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        if (get(client, port, "/api/v2/health").statusCode() == 200) {
          return;
        }
      } catch (IOException failure) {
        lastFailure = failure;
      }
      Thread.sleep(25);
    }
    if (lastFailure != null) {
      throw lastFailure;
    }
    throw new IllegalStateException("single-workspace HTTP service did not start");
  }

  private static String sha256(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte item : digest) {
      result.append(String.format("%02x", Byte.toUnsignedInt(item)));
    }
    return result.toString();
  }
}
