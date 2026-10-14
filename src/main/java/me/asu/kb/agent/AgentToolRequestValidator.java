package me.asu.kb.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.EngineeringWorkspaceCatalog;

/** Strictly decodes public tool fields before any application module is called. */
public final class AgentToolRequestValidator {
  private static final Set<String> OBJECT_TYPES =
      Set.of(
          "DOCUMENT", "SECTION", "CHUNK", "REQUIREMENT", "DESIGN", "CODE_SYMBOL", "TEST",
          "COMMIT", "CONFIGURATION", "OTHER");
  private static final Set<String> CONTEXT_INTENTS =
      Set.of("UNDERSTAND", "CHANGE", "REVIEW", "DIAGNOSE", "TEST");

  private final EngineeringWorkspace workspace;
  private final VirtualProjectLookup virtualProjectLookup;

  public AgentToolRequestValidator(EngineeringWorkspaceCatalog workspaceCatalog) {
    this(workspaceCatalog, projectId -> false);
  }

  public AgentToolRequestValidator(
      EngineeringWorkspaceCatalog workspaceCatalog, VirtualProjectLookup virtualProjectLookup) {
    Objects.requireNonNull(workspaceCatalog, "workspaceCatalog");
    this.virtualProjectLookup =
        Objects.requireNonNull(virtualProjectLookup, "virtualProjectLookup");
    List<EngineeringWorkspace> workspaces = workspaceCatalog.workspaces();
    if (workspaces.size() != 1) {
      throw new IllegalArgumentException("exactly one service workspace is required");
    }
    this.workspace = Objects.requireNonNull(workspaces.get(0), "workspace");
  }

  public ValidatedAgentToolRequest validate(
      String toolName,
      Map<String, Object> fields,
      int encodedRequestBytes,
      AgentCallDeadline deadline)
      throws AgentRequestException {
    Objects.requireNonNull(deadline, "deadline");
    deadline.ensureActive();
    AgentPayloadLimits.ensureRequestSize(encodedRequestBytes);
    if (fields == null) {
      throw invalid("INVALID_REQUEST", "request fields are required");
    }

    Object request = switch (toolName == null ? "" : toolName) {
      case "knowledge_search" -> knowledgeSearch(fields);
      case "knowledge_get" -> knowledgeGet(fields);
      case "code_symbol" -> codeSymbol(fields);
      case "code_search" -> codeSearch(fields);
      case "trace" -> trace(fields);
      case "build_context" -> buildContext(fields);
      default -> throw invalid("UNKNOWN_TOOL", "tool is not supported");
    };
    deadline.ensureActive();
    return new ValidatedAgentToolRequest(toolName, request, workspace, deadline);
  }

  private AgentToolValues.KnowledgeSearchRequest knowledgeSearch(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(
        fields,
        "query",
        "objectTypes",
        "resultLimit",
        "includeStale",
        "requireEvidence",
        "virtualProjectId");
    String virtualProjectId = optionalText(fields, "virtualProjectId", 256);
    if (!virtualProjectId.isEmpty()) {
      for (int index = 0; index < virtualProjectId.length(); index++) {
        if (Character.isISOControl(virtualProjectId.charAt(index))) {
          throw invalid("INVALID_PROJECT", "virtualProjectId is malformed");
        }
      }
      try {
        if (!virtualProjectLookup.exists(virtualProjectId)) {
          throw notFound("VIRTUAL_PROJECT_NOT_FOUND", "virtual project is not registered");
        }
      } catch (AgentRequestException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new AgentRequestException(
            AgentToolValues.Status.UNAVAILABLE,
            "VIRTUAL_PROJECT_UNAVAILABLE",
            "virtual project is temporarily unavailable");
      }
    }
    return new AgentToolValues.KnowledgeSearchRequest(
        workspace.id(),
        requiredText(fields, "query", 512),
        optionalEnumSet(fields, "objectTypes", OBJECT_TYPES, 32),
        optionalInteger(fields, "resultLimit", 1, 100, 0),
        optionalBoolean(fields, "includeStale", false),
        optionalBoolean(fields, "requireEvidence", false),
        virtualProjectId);
  }

  private AgentToolValues.KnowledgeGetRequest knowledgeGet(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(fields, "objectId");
    return new AgentToolValues.KnowledgeGetRequest(
        workspace.id(), objectId(fields, "objectId"));
  }

  private AgentToolValues.CodeSymbolRequest codeSymbol(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(fields, "query", "resultLimit");
    return new AgentToolValues.CodeSymbolRequest(
        workspace.id(),
        requiredText(fields, "query", 512),
        optionalInteger(fields, "resultLimit", 1, 100, 0));
  }

  private AgentToolValues.CodeSearchRequest codeSearch(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(fields, "literalQuery", "relativeScopes", "contextLines", "maxResults");
    String literal = requiredText(fields, "literalQuery", 512);
    if (literal.indexOf('\0') >= 0 || literal.indexOf('\n') >= 0 || literal.indexOf('\r') >= 0) {
      throw invalid("INVALID_LITERAL_QUERY", "literalQuery must be a single line");
    }
    return new AgentToolValues.CodeSearchRequest(
        workspace.id(),
        literal,
        relativeScopes(fields),
        optionalInteger(fields, "contextLines", 0, 3, 0),
        optionalInteger(fields, "maxResults", 1, 200, 0));
  }

  private AgentToolValues.TraceRequest trace(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(fields, "startObjectId");
    return new AgentToolValues.TraceRequest(
        workspace.id(), objectId(fields, "startObjectId"));
  }

  private AgentToolValues.BuildContextRequest buildContext(Map<String, Object> fields)
      throws AgentRequestException {
    rejectUnknown(fields, "target", "intent", "tokenBudget", "includeStale", "requireEvidence");
    return new AgentToolValues.BuildContextRequest(
        workspace.id(),
        requiredText(fields, "target", 512),
        requiredEnum(fields, "intent", CONTEXT_INTENTS),
        requiredInteger(fields, "tokenBudget", 256, 32_768),
        optionalBoolean(fields, "includeStale", false),
        optionalBoolean(fields, "requireEvidence", false));
  }

  private static List<String> relativeScopes(Map<String, Object> fields)
      throws AgentRequestException {
    Object value = fields.get("relativeScopes");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> raw) || raw.size() > 64) {
      throw invalid("INVALID_SCOPE", "relativeScopes must contain at most 64 paths");
    }
    List<String> scopes = new ArrayList<>(raw.size());
    for (Object item : raw) {
      if (!(item instanceof String scope) || scope.isBlank() || scope.length() > 512) {
        throw invalid("INVALID_SCOPE", "scope must be a non-blank relative path");
      }
      try {
        Path path = Path.of(scope);
        if (path.isAbsolute() || containsParent(path)) {
          throw invalid("INVALID_SCOPE", "scope must stay inside its workspace");
        }
        scopes.add(path.normalize().toString().replace('\\', '/'));
      } catch (InvalidPathException failure) {
        throw invalid("INVALID_SCOPE", "scope path is invalid");
      }
    }
    return List.copyOf(new LinkedHashSet<>(scopes));
  }

  private static String objectId(Map<String, Object> fields, String name)
      throws AgentRequestException {
    String value = requiredText(fields, name, 256);
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) {
        throw invalid("INVALID_OBJECT_ID", name + " is malformed");
      }
    }
    return value;
  }

  private static Set<String> optionalEnumSet(
      Map<String, Object> fields, String name, Set<String> allowed, int maximum)
      throws AgentRequestException {
    Object value = fields.get(name);
    if (value == null) {
      return Set.of();
    }
    if (!(value instanceof List<?> raw) || raw.size() > maximum) {
      throw invalid("INVALID_FILTER", name + " is invalid");
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (Object item : raw) {
      if (!(item instanceof String text) || !allowed.contains(text)) {
        throw invalid("INVALID_FILTER", name + " contains an unsupported value");
      }
      result.add(text);
    }
    return Set.copyOf(result);
  }

  private static String requiredEnum(Map<String, Object> fields, String name, Set<String> allowed)
      throws AgentRequestException {
    String value = requiredText(fields, name, 64);
    if (!allowed.contains(value)) {
      throw invalid("INVALID_ENUM", name + " is unsupported");
    }
    return value;
  }

  private static String requiredText(Map<String, Object> fields, String name, int maximum)
      throws AgentRequestException {
    Object value = fields.get(name);
    if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
      throw invalid("INVALID_FIELD", name + " must be non-blank text within its limit");
    }
    return text.trim();
  }

  private static String optionalText(Map<String, Object> fields, String name, int maximum)
      throws AgentRequestException {
    if (!fields.containsKey(name)) {
      return "";
    }
    Object value = fields.get(name);
    if (!(value instanceof String text) || text.length() > maximum || text.isBlank()) {
      throw invalid("INVALID_PROJECT", name + " must be non-blank text within its limit");
    }
    return text.trim();
  }

  private static int requiredInteger(
      Map<String, Object> fields, String name, int minimum, int maximum)
      throws AgentRequestException {
    if (!fields.containsKey(name)) {
      throw invalid("MISSING_FIELD", name + " is required");
    }
    return integer(fields.get(name), name, minimum, maximum);
  }

  private static int optionalInteger(
      Map<String, Object> fields, String name, int minimum, int maximum, int defaultValue)
      throws AgentRequestException {
    return fields.containsKey(name)
        ? integer(fields.get(name), name, minimum, maximum)
        : defaultValue;
  }

  private static int integer(Object value, String name, int minimum, int maximum)
      throws AgentRequestException {
    if (!(value instanceof Number number)) {
      throw invalid("INVALID_FIELD", name + " must be an integer");
    }
    long parsed = number.longValue();
    if (parsed != number.doubleValue() || parsed < minimum || parsed > maximum) {
      throw invalid("LIMIT_EXCEEDED", name + " is outside its allowed range");
    }
    return (int) parsed;
  }

  private static boolean optionalBoolean(
      Map<String, Object> fields, String name, boolean defaultValue) throws AgentRequestException {
    if (!fields.containsKey(name)) {
      return defaultValue;
    }
    Object value = fields.get(name);
    if (!(value instanceof Boolean flag)) {
      throw invalid("INVALID_FIELD", name + " must be a boolean");
    }
    return flag;
  }

  private static void rejectUnknown(Map<String, Object> fields, String... allowed)
      throws AgentRequestException {
    Set<String> names = Set.of(allowed);
    for (Object key : fields.keySet()) {
      if (!(key instanceof String name) || !names.contains(name)) {
        throw invalid("UNKNOWN_FIELD", "request contains an unsupported field");
      }
    }
  }

  private static boolean containsParent(Path path) {
    for (Path segment : path) {
      if ("..".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static AgentRequestException invalid(String code, String detail) {
    return new AgentRequestException(AgentToolValues.Status.INVALID_ARGUMENT, code, detail);
  }

  private static AgentRequestException notFound(String code, String detail) {
    return new AgentRequestException(AgentToolValues.Status.NOT_FOUND, code, detail);
  }

  @FunctionalInterface
  public interface VirtualProjectLookup {
    boolean exists(String projectId) throws Exception;
  }
}
