package me.asu.kb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.asu.kb.agent.AgentToolRequestValidator;
import me.asu.kb.agent.AgentToolValues;
import me.asu.kb.agent.KnowledgeAgentFacade;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.ImmutableEngineeringWorkspaceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeMcpToolHandlersTest {
  @TempDir Path root;

  @Test
  void dispatchesAllSixToolsAndRendersVersionedStructuredResponses() {
    RecordingFacade facade = new RecordingFacade();
    KnowledgeMcpToolHandlers handlers = handlers(facade);

    List<Map<String, Object>> requests =
        List.of(
            Map.of("query", "cache"),
            Map.of("objectId", "doc:1"),
            Map.of("query", "Parser"),
            Map.of("literalQuery", "needle"),
            Map.of("startObjectId", "symbol:1"),
            Map.of(
                "target", "Parser", "intent", "REVIEW", "tokenBudget", 512));
    List<String> names = List.copyOf(KnowledgeAgentToolSchemas.all().keySet());

    for (int index = 0; index < names.size(); index++) {
      var result = handlers.call(names.get(index), requests.get(index));
      Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
      assertEquals("1.0", structured.get("schemaVersion"));
      assertEquals("OK", structured.get("status"));
      assertFalse(structured.toString().contains("workspaceId"));
      assertFalse(result.isError());
    }
    assertEquals(names, facade.calls);
  }

  @Test
  void mapsValidationAndUnexpectedFailuresWithoutCallingThrough() {
    RecordingFacade facade = new RecordingFacade();
    KnowledgeMcpToolHandlers handlers = handlers(facade);

    var invalid =
        handlers.call(
            "knowledge_search",
            Map.of("query", "cache", "unsupported", true));
    var obsoleteWorkspace =
        handlers.call(
            "knowledge_search", Map.of("workspaceId", "repo", "query", "cache"));
    facade.fail = true;
    var internal =
        handlers.call(
            "knowledge_search", Map.of("query", "cache"));

    assertEquals("INVALID_ARGUMENT", status(invalid));
    assertEquals("INVALID_ARGUMENT", status(obsoleteWorkspace));
    assertEquals("INTERNAL", status(internal));
    assertTrue(invalid.isError());
    assertEquals(1, facade.calls.size());
    assertFalse(internal.content().toString().contains("SELECT secret"));
  }

  @Test
  void rejectsRequiredUnknownMalformedWorkspaceBudgetAndPayloadViolationsBeforeFacade() {
    RecordingFacade facade = new RecordingFacade();
    KnowledgeMcpToolHandlers handlers = handlers(facade);

    List<io.modelcontextprotocol.spec.McpSchema.CallToolResult> invalid =
        List.of(
            handlers.call("knowledge_search", Map.of()),
            handlers.call(
                "trace",
                Map.of("startObjectId", "symbol:1", "depth", 2)),
            handlers.call(
                "trace", Map.of("startObjectId", "bad\u0000id")),
            handlers.call(
                "build_context",
                Map.of(
                    "target",
                    "Parser",
                    "intent",
                    "REVIEW",
                    "tokenBudget",
                    32_769)),
            handlers.call(
                "knowledge_search",
                Map.of("query", "x".repeat(65_536))));
    var obsoleteWorkspace =
        handlers.call(
            "knowledge_search", Map.of("workspaceId", "repo", "query", "cache"));

    invalid.forEach(result -> assertEquals("INVALID_ARGUMENT", status(result)));
    assertEquals("INVALID_ARGUMENT", status(obsoleteWorkspace));
    assertTrue(facade.calls.isEmpty());
  }

  @Test
  void rejectsUnsafeOutputPathsAndSanitizesFacadeDiagnostics() {
    RecordingFacade facade = new RecordingFacade();
    facade.searchItems =
        List.of(
            new AgentToolValues.KnowledgeItem(
                "doc:1",
                "DOCUMENT",
                "title",
                "excerpt",
                new AgentToolValues.Source("source", root.resolve("secret.txt").toString(), 1, 1),
                "CURRENT",
                List.of("source"),
                List.of()));
    KnowledgeMcpToolHandlers handlers = handlers(facade);

    var unsafePath =
        handlers.call(
            "knowledge_search", Map.of("query", "cache"));
    assertEquals("INTERNAL", status(unsafePath));
    assertFalse(unsafePath.content().toString().contains(root.toString()));

    facade.searchItems = List.of();
    facade.diagnostics =
        List.of(
            new AgentToolValues.Diagnostic(
                "SOURCE_UNAVAILABLE", "SQLException SELECT secret FROM C:\\private\\store.db"));
    var sanitized =
        handlers.call(
            "knowledge_search", Map.of("query", "cache"));
    assertFalse(sanitized.content().toString().contains("private"));
    assertTrue(sanitized.content().toString().contains("operation did not complete"));
  }

  @Test
  void addsActionableDiagnosticWhenFacadeReturnsNonOkWithoutDetails() {
    RecordingFacade facade = new RecordingFacade();
    facade.responseStatus = AgentToolValues.Status.UNAVAILABLE;

    var result = handlers(facade).call("knowledge_search", Map.of("query", "cache"));
    Map<?, ?> structured = (Map<?, ?>) result.structuredContent();

    assertEquals("UNAVAILABLE", structured.get("status"));
    assertTrue(structured.get("diagnostics").toString().contains("check service readiness"));
    assertTrue(result.isError());
  }

  @Test
  void enforcesResultAndCompleteResponseLimits() {
    RecordingFacade facade = new RecordingFacade();
    AgentToolValues.KnowledgeItem item =
        new AgentToolValues.KnowledgeItem(
            "doc:1", "DOCUMENT", "title", "excerpt", null, "CURRENT", List.of("source"), List.of());
    facade.searchItems = new ArrayList<>(java.util.Collections.nCopies(101, item));
    KnowledgeMcpToolHandlers handlers = handlers(facade);

    var limited =
        handlers.call(
            "knowledge_search", Map.of("query", "cache"));
    Map<?, ?> limitedData = (Map<?, ?>) ((Map<?, ?>) limited.structuredContent()).get("data");
    assertEquals("INCOMPLETE", status(limited));
    assertEquals(100, ((List<?>) limitedData.get("items")).size());
    assertFalse(limited.isError());

    facade.searchItems =
        List.of(
            new AgentToolValues.KnowledgeItem(
                "doc:large",
                "DOCUMENT",
                "title",
                "x".repeat(1_048_576),
                null,
                "CURRENT",
                List.of("source"),
                List.of()));
    var oversized =
        handlers.call(
            "knowledge_search", Map.of("query", "cache"));
    assertEquals("INTERNAL", status(oversized));
    assertTrue(oversized.isError());
  }

  @Test
  void traceOmitsRelationsWithoutEvidenceAndMarksTheResultIncomplete() {
    RecordingFacade facade = new RecordingFacade();
    var source = new AgentToolValues.Source("source", "src/Parser.java", 1, 2);
    var endpoint =
        new AgentToolValues.TraceObject("symbol:1", "CODE_SYMBOL", "Parser", source, "CURRENT");
    var evidence =
        new AgentToolValues.Evidence(
            "SOURCE_RANGE", "source", "src/Parser.java", 1, 2, "Parser");
    facade.traceRelations =
        List.of(
            new AgentToolValues.TracedRelation(
                "relation:verified", endpoint, "REFERENCES", endpoint, List.of(evidence)),
            new AgentToolValues.TracedRelation(
                "relation:unverified", endpoint, "REFERENCES", endpoint, List.of()));

    var result =
        handlers(facade)
            .call("trace", Map.of("startObjectId", "symbol:1"));
    Map<?, ?> structured = (Map<?, ?>) result.structuredContent();
    Map<?, ?> data = (Map<?, ?>) structured.get("data");
    List<?> relations = (List<?>) data.get("relations");
    List<?> diagnostics = (List<?>) structured.get("diagnostics");

    assertEquals("INCOMPLETE", structured.get("status"));
    assertEquals(1, relations.size());
    assertTrue(diagnostics.toString().contains("TRACE_EVIDENCE_REQUIRED"));
    assertFalse(result.isError());
  }

  private KnowledgeMcpToolHandlers handlers(KnowledgeAgentFacade facade) {
    var catalog =
        new ImmutableEngineeringWorkspaceCatalog(List.of(new EngineeringWorkspace("repo", root)));
    return new KnowledgeMcpToolHandlers(facade, new AgentToolRequestValidator(catalog));
  }

  private static Object status(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
    return ((Map<?, ?>) result.structuredContent()).get("status");
  }

  private static final class RecordingFacade implements KnowledgeAgentFacade {
    private final List<String> calls = new ArrayList<>();
    private List<AgentToolValues.KnowledgeItem> searchItems = List.of();
    private List<AgentToolValues.TracedRelation> traceRelations = List.of();
    private List<AgentToolValues.Diagnostic> diagnostics = List.of();
    private AgentToolValues.Status responseStatus = AgentToolValues.Status.OK;
    private boolean fail;

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeSearchData> knowledgeSearch(
        AgentToolValues.KnowledgeSearchRequest request) {
      calls.add("knowledge_search");
      if (fail) {
        throw new IllegalStateException("SELECT secret FROM internal_table");
      }
      return new AgentToolValues.ToolResponse<>(
          "wrong",
          responseStatus,
          true,
          new AgentToolValues.KnowledgeSearchData(request.workspaceId(), searchItems),
          diagnostics);
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeGetData> knowledgeGet(
        AgentToolValues.KnowledgeGetRequest request) {
      calls.add("knowledge_get");
      return ok(new AgentToolValues.KnowledgeGetData(request.workspaceId(), null));
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.CodeSymbolData> codeSymbol(
        AgentToolValues.CodeSymbolRequest request) {
      calls.add("code_symbol");
      return ok(new AgentToolValues.CodeSymbolData(request.workspaceId(), List.of()));
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.CodeSearchData> codeSearch(
        AgentToolValues.CodeSearchRequest request) {
      calls.add("code_search");
      return ok(new AgentToolValues.CodeSearchData(request.workspaceId(), List.of(), false));
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.TraceData> trace(
        AgentToolValues.TraceRequest request) {
      calls.add("trace");
      return ok(new AgentToolValues.TraceData(request.workspaceId(), null, traceRelations));
    }

    @Override
    public AgentToolValues.ToolResponse<AgentToolValues.BuildContextData> buildContext(
        AgentToolValues.BuildContextRequest request) {
      calls.add("build_context");
      return ok(
          new AgentToolValues.BuildContextData(
              request.workspaceId(),
              request.target(),
              request.intent(),
              List.of(),
              0,
              "utf8-bytes-v1",
              List.of()));
    }

    private static <T> AgentToolValues.ToolResponse<T> ok(T data) {
      return new AgentToolValues.ToolResponse<>("wrong", AgentToolValues.Status.OK, true, data, List.of());
    }
  }
}
