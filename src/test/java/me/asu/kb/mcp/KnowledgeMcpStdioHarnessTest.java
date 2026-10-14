package me.asu.kb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asu.kb.agent.AgentToolRequestValidator;
import me.asu.kb.agent.AgentToolValues;
import me.asu.kb.agent.KnowledgeAgentFacade;
import me.asu.kb.adapter.document.LegacyDocumentSnapshot;
import me.asu.kb.adapter.document.LegacySourceProvenance;
import me.asu.kb.ingestion.DocumentIngestionUnit;
import me.asu.kb.ingestion.IngestionRequest;
import me.asu.kb.runtime.SingleWorkspaceKnowledgeService;
import me.asu.kb.runtime.SingleWorkspaceLauncher;
import me.asu.kb.runtime.SingleWorkspaceServiceConfig;
import me.asu.kb.service.Article;
import me.asu.kb.service.ArticleId;
import me.asu.kb.service.ArticleMetadata;
import me.asu.kb.service.ArticleRevision;
import me.asu.kb.service.ArticleSourceKind;
import me.asu.kb.service.ArticleState;
import me.asu.kb.service.ProjectionState;
import me.asu.kb.service.ProjectionStatus;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.ImmutableEngineeringWorkspaceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeMcpStdioHarnessTest {
  private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();
  private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

  @TempDir Path root;

  @Test
  void productionCompositionSharesIngestedStateAcrossAllSixWorkspaceFreeTools()
      throws Exception {
    Path workspace = root.resolve("production-workspace");
    SingleWorkspaceLauncher.Configuration configuration =
        SingleWorkspaceLauncher.load(
            new String[] {
              "--workspace-root", workspace.toString(), "--llm-provider", "echo"
            });
    try (SingleWorkspaceKnowledgeService application =
            SingleWorkspaceLauncher.open(configuration);
        Harness harness =
            new Harness(
                new KnowledgeMcpToolHandlers(
                    application.agentFacade(),
                new AgentToolRequestValidator(configuration.service().workspaceCatalog())),
                Duration.ofSeconds(2))) {
      Files.writeString(
          workspace.resolve("Sample.java"),
          "final class Sample { void deterministicParser() {} }",
          StandardCharsets.UTF_8);
      assertTrue(
          application
              .projectionIngestion()
              .ingest(
                  IngestionRequest.of(
                      SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                      List.of(documentUnit())))
              .fullySuccessful());
      harness.initialize();

      String objectId = "document:v1:sha256:" + sha256("doc:mcp-integration");
      Map<String, Map<String, Object>> calls =
          Map.of(
              "knowledge_search", Map.of("query", "deterministic", "resultLimit", 5),
              "knowledge_get", Map.of("objectId", objectId),
              "code_symbol", Map.of("query", "Parser", "resultLimit", 5),
              "code_search", Map.of("literalQuery", "needle", "maxResults", 5),
              "trace", Map.of("startObjectId", objectId),
              "build_context",
                  Map.of(
                      "target",
                      "deterministic",
                      "intent",
                      "UNDERSTAND",
                      "tokenBudget",
                      512));

      int id = 10;
      for (Map.Entry<String, Map<String, Object>> entry : calls.entrySet()) {
        harness.send(call(id, entry.getKey(), entry.getValue()));
        Map<?, ?> response = harness.responseWithId(id++);
        Object status = toolStatus(response);
        if ("code_symbol".equals(entry.getKey())) {
          assertEquals("INCOMPLETE", status);
          assertTrue(
              response.toString().contains("SOURCE_FAILED")
                  && response.toString().contains("Ctags returned diagnostics"),
              entry.getKey() + " returned " + response);
        } else if ("code_search".equals(entry.getKey())) {
          assertEquals("INCOMPLETE", status);
          assertTrue(
              response.toString().contains("TOOL_UNAVAILABLE"),
              entry.getKey() + " returned " + response);
        } else {
          assertTrue(
              "OK".equals(status) || "INCOMPLETE".equals(status),
              entry.getKey() + " returned " + response);
        }
        assertFalse(response.toString().contains("workspaceId"));
      }
      assertTrue(harness.protocolTranscript().contains(objectId));
      assertFalse(harness.protocolTranscript().contains("Exception"));
    }
  }

  @Test
  void stdioRemainsProtocolOnlyMapsPartialFailureAndSurvivesFailedCalls() throws Exception {
    FixtureFacade facade = new FixtureFacade();
    try (Harness harness = harness(facade, Duration.ofSeconds(2))) {
      harness.initialize();

      harness.send(call(2, Map.of("workspaceId", "repo", "query", "normal")));
      Map<?, ?> rejected = harness.response();
      assertTrue(rejected.get("result") instanceof Map<?, ?>, rejected.toString());
      Map<?, ?> rejectedResult = (Map<?, ?>) rejected.get("result");
      assertEquals(Boolean.TRUE, rejectedResult.get("isError"));
      assertFalse(rejectedResult.containsKey("structuredContent"));
      assertEquals(0, facade.readCalls);

      harness.send(call(3, Map.of("query", "partial")));
      Map<?, ?> partial = harness.response();
      assertEquals("INCOMPLETE", toolStatus(partial));
      assertTrue(partial.toString().contains("SOURCE_UNAVAILABLE"));

      harness.send(call(4, Map.of("query", "normal")));
      Map<?, ?> recovered = harness.response();
      assertEquals("OK", toolStatus(recovered));
      assertEquals(2, facade.readCalls);
      assertEquals(0, facade.writeCalls);
      assertFalse(harness.protocolTranscript().contains("Exception"));
    }
  }

  @Test
  void protocolCancellationIsBestEffortAndServerRemainsAvailable() throws Exception {
    FixtureFacade facade = new FixtureFacade();
    try (Harness harness = harness(facade, Duration.ofSeconds(2))) {
      harness.initialize();
      harness.send(call(5, Map.of("query", "slow")));
      assertTrue(facade.slowCallEntered.await(1, TimeUnit.SECONDS));
      harness.send(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
              + "\"params\":{\"requestId\":5,\"reason\":\"test cancellation\"}}");
      assertFalse(facade.interrupted.await(150, TimeUnit.MILLISECONDS));
      facade.releaseSlowCall.countDown();

      harness.send(call(6, Map.of("query", "normal")));
      Map<?, ?> recovered = harness.responseWithId(6);
      assertEquals("OK", toolStatus(recovered));
      assertEquals(0, facade.writeCalls);
    }
  }

  @Test
  void sdkDeadlineBoundsTheCallAndLeavesTheStdioSessionUsable() throws Exception {
    FixtureFacade facade = new FixtureFacade();
    try (Harness harness = harness(facade, Duration.ofMillis(100))) {
      harness.initialize();
      harness.send(call(7, Map.of("query", "slow")));
      Map<?, ?> timeout = harness.response();
      assertEquals(7, ((Number) timeout.get("id")).intValue());
      assertTrue(timeout.containsKey("error"));
      assertTrue(facade.interrupted.await(1, TimeUnit.SECONDS));

      harness.send(call(8, Map.of("query", "normal")));
      Map<?, ?> recovered = harness.responseWithId(8);
      assertEquals("OK", toolStatus(recovered));
      assertEquals(0, facade.writeCalls);
    }
  }

  private Harness harness(FixtureFacade facade, Duration timeout) throws IOException {
    var catalog =
        new ImmutableEngineeringWorkspaceCatalog(List.of(new EngineeringWorkspace("repo", root)));
    return new Harness(
        new KnowledgeMcpToolHandlers(facade, new AgentToolRequestValidator(catalog)), timeout);
  }

  private static String call(int id, Map<String, Object> arguments) throws IOException {
    return call(id, "knowledge_search", arguments);
  }

  private static String call(int id, String toolName, Map<String, Object> arguments)
      throws IOException {
    return JSON.writeValueAsString(
        Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", toolName, "arguments", arguments)));
  }

  private static DocumentIngestionUnit documentUnit() {
    ArticleRevision revision =
        new ArticleRevision(
            1,
            "document-hash",
            "markdown",
            "docs/mcp-service.md",
            ArticleSourceKind.ROOT,
            NOW);
    Article article =
        new Article(
            new ArticleId(1),
            revision,
            new ArticleMetadata(
                Optional.of("Deterministic MCP Service"),
                Optional.empty(),
                List.of(),
                List.of()),
            ArticleState.ACTIVE,
            new ProjectionStatus(ProjectionState.INDEXED, 1, Optional.empty(), NOW));
    return new DocumentIngestionUnit(
        new LegacyDocumentSnapshot(
            "doc:mcp-integration",
            article,
            "# Deterministic MCP Service\nShared production ingestion and retrieval state.",
            Optional.of(
                new LegacySourceProvenance(
                    Optional.of("workspace-file"),
                    Optional.of("docs/mcp-service.md"),
                    Optional.of("revision-1"))),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("docs/mcp-service.md")));
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

  private static Object toolStatus(Map<?, ?> response) {
    Map<?, ?> result = (Map<?, ?>) response.get("result");
    Map<?, ?> structured = (Map<?, ?>) result.get("structuredContent");
    return structured.get("status");
  }

  private static final class Harness implements AutoCloseable {
    private final PipedOutputStream clientOutput;
    private final PipedInputStream serverInput;
    private final PipedOutputStream serverOutput;
    private final PipedInputStream clientInput;
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final KnowledgeMcpServer server;

    private Harness(KnowledgeMcpToolHandler handler, Duration timeout) throws IOException {
      clientOutput = new PipedOutputStream();
      serverInput = new PipedInputStream(clientOutput);
      serverOutput = new PipedOutputStream();
      clientInput = new PipedInputStream(serverOutput);
      server = KnowledgeMcpServer.start(serverInput, serverOutput, handler, timeout);
    }

    private void initialize() throws Exception {
      send(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
              + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
              + "\"clientInfo\":{\"name\":\"test-harness\",\"version\":\"1.0\"}}}");
      Map<?, ?> initialized = response();
      assertEquals(1, ((Number) initialized.get("id")).intValue());
      send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    private void send(String message) throws IOException {
      clientOutput.write((message + "\n").getBytes(StandardCharsets.UTF_8));
      clientOutput.flush();
    }

    private Map<?, ?> responseWithId(int expectedId) throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < end) {
        Map<?, ?> response = response();
        if (response.get("id") instanceof Number id && id.intValue() == expectedId) {
          return response;
        }
      }
      throw new AssertionError("response " + expectedId + " was not received");
    }

    private Map<?, ?> response() throws Exception {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < end) {
        if (clientInput.available() == 0) {
          Thread.sleep(5);
          continue;
        }
        int value = clientInput.read();
        if (value == '\n') {
          byte[] bytes = line.toByteArray();
          transcript.write(bytes);
          transcript.write('\n');
          return JSON.readValue(bytes, Map.class);
        }
        if (value >= 0 && value != '\r') {
          line.write(value);
        }
      }
      throw new AssertionError("protocol response timed out");
    }

    private String protocolTranscript() {
      return transcript.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
      server.close();
      clientOutput.close();
      clientInput.close();
      serverInput.close();
      serverOutput.close();
    }
  }

  private static final class FixtureFacade implements KnowledgeAgentFacade {
    private final CountDownLatch slowCallEntered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch releaseSlowCall = new CountDownLatch(1);
    private final AtomicBoolean slowOnce = new AtomicBoolean(true);
    private int readCalls;
    private int writeCalls;

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeSearchData> knowledgeSearch(
        AgentToolValues.KnowledgeSearchRequest request) {
      readCalls++;
      if ("slow".equals(request.query()) && slowOnce.compareAndSet(true, false)) {
        slowCallEntered.countDown();
        try {
          releaseSlowCall.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
          interrupted.countDown();
          Thread.currentThread().interrupt();
        }
      }
      boolean partial = "partial".equals(request.query());
      return new AgentToolValues.ToolResponse<>(
          AgentToolValues.SCHEMA_VERSION,
          partial ? AgentToolValues.Status.INCOMPLETE : AgentToolValues.Status.OK,
          !partial,
          new AgentToolValues.KnowledgeSearchData(request.workspaceId(), List.of()),
          partial
              ? List.of(
                  new AgentToolValues.Diagnostic(
                      "SOURCE_UNAVAILABLE", "source is temporarily unavailable"))
              : List.of());
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeGetData> knowledgeGet(
        AgentToolValues.KnowledgeGetRequest request) {
      throw new AssertionError("unexpected tool");
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.CodeSymbolData> codeSymbol(
        AgentToolValues.CodeSymbolRequest request) {
      throw new AssertionError("unexpected tool");
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.CodeSearchData> codeSearch(
        AgentToolValues.CodeSearchRequest request) {
      throw new AssertionError("unexpected tool");
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.TraceData> trace(
        AgentToolValues.TraceRequest request) {
      throw new AssertionError("unexpected tool");
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.BuildContextData> buildContext(
        AgentToolValues.BuildContextRequest request) {
      throw new AssertionError("unexpected tool");
    }
  }
}
