package me.asu.kb.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.agent.AgentCallDeadline;
import me.asu.kb.agent.AgentPayloadLimits;
import me.asu.kb.agent.AgentRequestException;
import me.asu.kb.agent.AgentToolRequestValidator;
import me.asu.kb.agent.AgentToolValues;
import me.asu.kb.agent.KnowledgeAgentFacade;
import me.asu.kb.agent.ValidatedAgentToolRequest;

/** Maps the fixed MCP tool set to the transport-independent application facade. */
public final class KnowledgeMcpToolHandlers implements KnowledgeMcpToolHandler {
  private static final int MAXIMUM_DIAGNOSTICS = 128;

  private final KnowledgeAgentFacade facade;
  private final AgentToolRequestValidator validator;
  private final McpJsonMapper mapper;

  public KnowledgeMcpToolHandlers(
      KnowledgeAgentFacade facade, AgentToolRequestValidator validator) {
    this(facade, validator, new JacksonMcpJsonMapperSupplier().get());
  }

  KnowledgeMcpToolHandlers(
      KnowledgeAgentFacade facade, AgentToolRequestValidator validator, McpJsonMapper mapper) {
    this.facade = Objects.requireNonNull(facade, "facade");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments) {
    AgentCallDeadline deadline = AgentCallDeadline.start();
    try {
      Map<String, Object> fields = arguments == null ? Map.of() : arguments;
      int requestBytes = mapper.writeValueAsBytes(fields).length;
      ValidatedAgentToolRequest validated =
          validator.validate(toolName, fields, requestBytes, deadline);
      deadline.ensureActive();
      AgentToolValues.ToolResponse<?> response = invoke(validated);
      deadline.ensureActive();
      return render(limit(toolName, response));
    } catch (AgentRequestException failure) {
      return renderControlled(toolName, arguments, failure.status(), failure.code(), failure.getMessage());
    } catch (IOException | RuntimeException failure) {
      return renderControlled(
          toolName,
          arguments,
          AgentToolValues.Status.INTERNAL,
          "INTERNAL_ERROR",
          "operation did not complete");
    }
  }

  private AgentToolValues.ToolResponse<?> invoke(ValidatedAgentToolRequest validated) {
    return switch (validated.toolName()) {
      case "knowledge_search" ->
          facade.knowledgeSearch(
              validated.requestAs(AgentToolValues.KnowledgeSearchRequest.class));
      case "knowledge_get" ->
          facade.knowledgeGet(validated.requestAs(AgentToolValues.KnowledgeGetRequest.class));
      case "code_symbol" ->
          facade.codeSymbol(validated.requestAs(AgentToolValues.CodeSymbolRequest.class));
      case "code_search" ->
          facade.codeSearch(validated.requestAs(AgentToolValues.CodeSearchRequest.class));
      case "trace" -> facade.trace(validated.requestAs(AgentToolValues.TraceRequest.class));
      case "build_context" ->
          facade.buildContext(validated.requestAs(AgentToolValues.BuildContextRequest.class));
      default -> throw new IllegalStateException("validated an unsupported tool");
    };
  }

  private AgentToolValues.ToolResponse<?> limit(
      String toolName, AgentToolValues.ToolResponse<?> response) {
    Objects.requireNonNull(response, "facade response");
    List<AgentToolValues.Diagnostic> diagnostics =
        prefix(
            response.diagnostics().stream()
                .map(KnowledgeMcpToolHandlers::safeDiagnostic)
                .toList(),
            MAXIMUM_DIAGNOSTICS);
    if (response.status() != AgentToolValues.Status.OK && diagnostics.isEmpty()) {
      diagnostics = List.of(statusDiagnostic(response.status()));
    }
    boolean resultLimited = response.diagnostics().size() > diagnostics.size();
    boolean incomplete = resultLimited;
    Object data;
    switch (toolName) {
      case "knowledge_search" -> {
        var value = (AgentToolValues.KnowledgeSearchData) response.data();
        var items = prefix(value.items(), 100);
        resultLimited |= value.items().size() > items.size();
        incomplete |= resultLimited;
        data = new AgentToolValues.KnowledgeSearchData(value.workspaceId(), items);
      }
      case "knowledge_get" -> data = (AgentToolValues.KnowledgeGetData) response.data();
      case "code_symbol" -> {
        var value = (AgentToolValues.CodeSymbolData) response.data();
        var symbols = prefix(value.symbols(), 100);
        resultLimited |= value.symbols().size() > symbols.size();
        incomplete |= resultLimited;
        data = new AgentToolValues.CodeSymbolData(value.workspaceId(), symbols);
      }
      case "code_search" -> {
        var value = (AgentToolValues.CodeSearchData) response.data();
        var matches = prefix(value.matches(), 200);
        resultLimited |= value.matches().size() > matches.size();
        incomplete |= resultLimited;
        data =
            new AgentToolValues.CodeSearchData(
                value.workspaceId(), matches, value.truncated() || resultLimited);
      }
      case "trace" -> {
        var value = (AgentToolValues.TraceData) response.data();
        var evidenceBearing =
            value.relations().stream()
                .filter(relation -> relation != null && !relation.evidence().isEmpty())
                .toList();
        boolean evidenceRejected = evidenceBearing.size() != value.relations().size();
        var relations = prefix(evidenceBearing, 64);
        resultLimited |= evidenceBearing.size() > relations.size();
        incomplete |= evidenceRejected || resultLimited;
        if (evidenceRejected) {
          diagnostics =
              withDiagnostic(
                  diagnostics,
                  new AgentToolValues.Diagnostic(
                      "TRACE_EVIDENCE_REQUIRED",
                      "relations without evidence were omitted"));
        }
        data = new AgentToolValues.TraceData(value.workspaceId(), value.startObject(), relations);
      }
      case "build_context" -> {
        var value = (AgentToolValues.BuildContextData) response.data();
        var items = prefix(value.items(), 256);
        var omissions = prefix(value.omissions(), 256);
        resultLimited |=
            value.items().size() > items.size() || value.omissions().size() > omissions.size();
        incomplete |= resultLimited;
        data =
            new AgentToolValues.BuildContextData(
                value.workspaceId(),
                value.target(),
                value.intent(),
                items,
                value.estimatedTokens(),
                value.estimatorVersion(),
                omissions);
      }
      default -> throw new IllegalArgumentException("unsupported tool");
    }
    if (resultLimited) {
      diagnostics = withLimitDiagnostic(diagnostics);
    }
    AgentToolValues.Status status =
        incomplete && response.status() == AgentToolValues.Status.OK
            ? AgentToolValues.Status.INCOMPLETE
            : response.status();
    return new AgentToolValues.ToolResponse<>(
        AgentToolValues.SCHEMA_VERSION,
        status,
        response.complete() && !incomplete,
        data,
        diagnostics);
  }

  private McpSchema.CallToolResult render(AgentToolValues.ToolResponse<?> response)
      throws AgentRequestException, IOException {
    Object structured = withoutTransportInternals(mapper.convertValue(response, Map.class));
    ensureSafeRelativePaths(structured);
    byte[] json = mapper.writeValueAsBytes(structured);
    AgentPayloadLimits.ensureCompleteResponseSize(json.length);
    return McpSchema.CallToolResult.builder()
        .structuredContent(structured)
        .textContent(List.of(new String(json, java.nio.charset.StandardCharsets.UTF_8)))
        .isError(isError(response.status()))
        .build();
  }

  private McpSchema.CallToolResult renderControlled(
      String toolName,
      Map<String, Object> arguments,
      AgentToolValues.Status status,
      String code,
      String detail) {
    AgentToolValues.ToolResponse<?> response =
        new AgentToolValues.ToolResponse<>(
            AgentToolValues.SCHEMA_VERSION,
            status,
            false,
            emptyData(toolName),
            List.of(new AgentToolValues.Diagnostic(code, safeDetail(detail))));
    try {
      return render(response);
    } catch (AgentRequestException | IOException ignored) {
      return McpSchema.CallToolResult.builder()
          .textContent(List.of("{\"schemaVersion\":\"1.0\",\"status\":\"INTERNAL\",\"complete\":false}"))
          .isError(true)
          .build();
    }
  }

  private static Object emptyData(String toolName) {
    String internalWorkspaceId = "service";
    return switch (toolName == null ? "" : toolName) {
      case "knowledge_get" -> new AgentToolValues.KnowledgeGetData(internalWorkspaceId, null);
      case "code_symbol" -> new AgentToolValues.CodeSymbolData(internalWorkspaceId, List.of());
      case "code_search" -> new AgentToolValues.CodeSearchData(internalWorkspaceId, List.of(), false);
      case "trace" -> new AgentToolValues.TraceData(internalWorkspaceId, null, List.of());
      case "build_context" ->
          new AgentToolValues.BuildContextData(
              internalWorkspaceId,
              "unavailable",
              "UNDERSTAND",
              List.of(),
              0,
              "unavailable",
              List.of());
      default -> new AgentToolValues.KnowledgeSearchData(internalWorkspaceId, List.of());
    };
  }

  private static String safeDetail(String detail) {
    if (detail == null || detail.isBlank() || detail.length() > 4096) {
      return "operation did not complete";
    }
    String normalized = detail.trim();
    String lower = normalized.toLowerCase(java.util.Locale.ROOT);
    if (normalized.startsWith("/")
        || normalized.matches("(?i).*[a-z]:[\\\\/].*")
        || lower.contains("exception")
        || lower.contains("stack trace")
        || lower.contains("select ")
        || lower.contains("insert ")
        || lower.contains("update ")
        || lower.contains("delete ")) {
      return "operation did not complete";
    }
    return normalized;
  }

  private static AgentToolValues.Diagnostic safeDiagnostic(
      AgentToolValues.Diagnostic diagnostic) {
    if (diagnostic == null) {
      return new AgentToolValues.Diagnostic("UNAVAILABLE", "operation did not complete");
    }
    String code = diagnostic.code();
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
      code = "UNAVAILABLE";
    }
    return new AgentToolValues.Diagnostic(code, safeDetail(diagnostic.detail()));
  }

  private static AgentToolValues.Diagnostic statusDiagnostic(AgentToolValues.Status status) {
    return switch (status) {
      case INVALID_ARGUMENT ->
          new AgentToolValues.Diagnostic(
              "INVALID_ARGUMENT", "correct the tool fields and retry the request");
      case NOT_FOUND ->
          new AgentToolValues.Diagnostic(
              "NOT_FOUND", "verify the object or virtual project identifier and retry");
      case UNAVAILABLE ->
          new AgentToolValues.Diagnostic(
              "UNAVAILABLE", "check service readiness and retry when the source is available");
      case INCOMPLETE ->
          new AgentToolValues.Diagnostic(
              "INCOMPLETE", "use the bounded result and inspect diagnostics before retrying");
      case DEADLINE_EXCEEDED ->
          new AgentToolValues.Diagnostic(
              "DEADLINE_EXCEEDED", "reduce the request budget or scope and retry");
      case CANCELLED ->
          new AgentToolValues.Diagnostic(
              "CANCELLED", "the operation was interrupted; retry only if the request is still needed");
      case INTERNAL ->
          new AgentToolValues.Diagnostic(
              "INTERNAL", "retry once; if the problem persists, inspect service diagnostics");
      case OK -> throw new IllegalArgumentException("OK does not need a fallback diagnostic");
    };
  }

  private static boolean isError(AgentToolValues.Status status) {
    return status != AgentToolValues.Status.OK && status != AgentToolValues.Status.INCOMPLETE;
  }

  private static <T> List<T> prefix(List<T> values, int maximum) {
    if (values.size() <= maximum) {
      return values;
    }
    return List.copyOf(values.subList(0, maximum));
  }

  private static Object withoutTransportInternals(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getValue() != null && !"workspaceId".equals(entry.getKey())) {
          result.put(
              String.valueOf(entry.getKey()), withoutTransportInternals(entry.getValue()));
        }
      }
      return Map.copyOf(result);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(KnowledgeMcpToolHandlers::withoutTransportInternals).toList();
    }
    return value;
  }

  private static void ensureSafeRelativePaths(Object value) throws AgentRequestException {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if ("relativePath".equals(entry.getKey()) && entry.getValue() instanceof String path) {
          ensureSafeRelativePath(path);
        }
        ensureSafeRelativePaths(entry.getValue());
      }
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        ensureSafeRelativePaths(item);
      }
    }
  }

  private static void ensureSafeRelativePath(String rawPath) throws AgentRequestException {
    try {
      Path path = Path.of(rawPath);
      if (rawPath.isBlank() || path.isAbsolute()) {
        throw unsafeOutput();
      }
      for (Path segment : path) {
        if ("..".equals(segment.toString())) {
          throw unsafeOutput();
        }
      }
    } catch (InvalidPathException failure) {
      throw unsafeOutput();
    }
  }

  private static AgentRequestException unsafeOutput() {
    return new AgentRequestException(
        AgentToolValues.Status.INTERNAL, "UNSAFE_OUTPUT", "output path is unavailable");
  }

  private static List<AgentToolValues.Diagnostic> withLimitDiagnostic(
      List<AgentToolValues.Diagnostic> diagnostics) {
    return withDiagnostic(
        diagnostics,
        new AgentToolValues.Diagnostic(
            "RESULT_LIMIT_REACHED", "result was truncated at the tool limit"));
  }

  private static List<AgentToolValues.Diagnostic> withDiagnostic(
      List<AgentToolValues.Diagnostic> diagnostics, AgentToolValues.Diagnostic diagnostic) {
    List<AgentToolValues.Diagnostic> result = new ArrayList<>(diagnostics);
    if (result.size() == MAXIMUM_DIAGNOSTICS) {
      result.remove(result.size() - 1);
    }
    result.add(diagnostic);
    return List.copyOf(result);
  }
}
