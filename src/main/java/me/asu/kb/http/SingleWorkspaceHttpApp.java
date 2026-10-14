package me.asu.kb.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import me.asu.http.Application;
import me.asu.http.ContextHandler;
import me.asu.http.MimeTypeDetector;
import me.asu.http.Request;
import me.asu.http.Response;
import me.asu.json.OKJSON;
import me.asu.kb.agent.AgentToolValues;
import me.asu.kb.runtime.ServiceReadiness;
import me.asu.kb.runtime.SingleWorkspaceApplication;
import me.asu.kb.runtime.SingleWorkspaceDocumentApplication;
import me.asu.kb.runtime.SingleWorkspaceKnowledgeService;
import me.asu.kb.runtime.SingleWorkspaceServiceConfig;
import me.asu.kb.runtime.VirtualProjectApplication;
import me.asu.kb.service.KnowledgeBase;

/** Breaking workspace-free HTTP transport mounted only under {@code /api/v2}. */
public final class SingleWorkspaceHttpApp implements AutoCloseable {
  private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(10);
  private static final int JSON_OPTIONS =
      OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE | OKJSON.OPTIONS_NULLABLE;
  private static final List<String> OBSOLETE_FIELDS =
      List.of("workspaceId", "workspace", "workspace_id");

  private final Application server;
  private final SingleWorkspaceApplication application;
  private final Supplier<ServiceReadiness> readiness;
  private final AutoCloseable composition;
  private final int port;
  private boolean closed;

  public SingleWorkspaceHttpApp(SingleWorkspaceKnowledgeService composition, int port)
      throws IOException {
    this(composition, composition::readiness, composition, port);
  }

  SingleWorkspaceHttpApp(
      SingleWorkspaceApplication application,
      Supplier<ServiceReadiness> readiness,
      AutoCloseable composition,
      int port)
      throws IOException {
    this.application = Objects.requireNonNull(application, "application");
    this.readiness = Objects.requireNonNull(readiness, "readiness");
    this.composition = Objects.requireNonNull(composition, "composition");
    this.port = port;
    this.server = new Application(port);
    registerRoutes();
  }

  public int start() throws IOException {
    Application.AppConfig config = new Application.AppConfig();
    config.setPort(port);
    return server.run(config);
  }

  private void registerRoutes() {
    server.addRoute("/{*}", this::root, "GET");
    server.addRoute("/webui/{*}", this::bundledWebUi, "GET");
    server.addRoute("/api/v2/health", guarded(this::health), "GET");
    server.addRoute("/api/v2/ready", guarded(this::ready), "GET");
    server.addRoute("/api/v2/status", guarded(this::status), "GET");
    server.addRoute(
        "/api/v2/knowledge/search", guarded(this::knowledgeSearch), "GET", "POST");
    server.addRoute("/api/v2/knowledge/objects/{id}", guarded(this::knowledgeGet), "GET");
    server.addRoute("/api/v2/knowledge/trace/{id}", guarded(this::trace), "GET");
    server.addRoute("/api/v2/knowledge/context", guarded(this::context), "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects", VirtualProjectApplication.Operation.LIST_PROJECTS, "GET");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects", VirtualProjectApplication.Operation.CREATE_PROJECT, "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}", VirtualProjectApplication.Operation.GET_PROJECT, "GET");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}", VirtualProjectApplication.Operation.UPDATE_PROJECT, "PATCH");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}", VirtualProjectApplication.Operation.DELETE_PROJECT, "DELETE");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/status",
        VirtualProjectApplication.Operation.GET_PROJECT_STATUS,
        "GET");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/sync",
        VirtualProjectApplication.Operation.SYNCHRONIZE_PROJECT,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/resources",
        VirtualProjectApplication.Operation.LIST_RESOURCES,
        "GET");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/resources",
        VirtualProjectApplication.Operation.ADD_RESOURCE,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/resources/{referenceId}",
        VirtualProjectApplication.Operation.REMOVE_RESOURCE,
        "DELETE");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/files",
        VirtualProjectApplication.Operation.CREATE_FILE,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/files/append",
        VirtualProjectApplication.Operation.APPEND_FILE,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/files/replace",
        VirtualProjectApplication.Operation.REPLACE_FILE,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/files/edit",
        VirtualProjectApplication.Operation.EDIT_FILE,
        "POST");
    addVirtualProjectRoute(
        "/api/v2/virtual-projects/{id}/files",
        VirtualProjectApplication.Operation.DELETE_FILE,
        "DELETE");
    addDocumentRoute("/api/v2/ingest/file", SingleWorkspaceDocumentApplication.Operation.INGEST_FILE, "POST");
    addDocumentRoute("/api/v2/ingest/dir", SingleWorkspaceDocumentApplication.Operation.INGEST_DIRECTORY, "POST");
    addDocumentRoute("/api/v2/index/rebuild", SingleWorkspaceDocumentApplication.Operation.REBUILD_INDEX, "POST");
    addDocumentRoute("/api/v2/roots", SingleWorkspaceDocumentApplication.Operation.LIST_ROOTS, "GET");
    addDocumentRoute("/api/v2/roots/remove", SingleWorkspaceDocumentApplication.Operation.REMOVE_ROOT, "POST");
    addDocumentRoute("/api/v2/documents", SingleWorkspaceDocumentApplication.Operation.LIST_DOCUMENTS, "GET");
    addDocumentRoute("/api/v2/documents", SingleWorkspaceDocumentApplication.Operation.CREATE_DOCUMENT, "POST");
    addDocumentRoute("/api/v2/documents/{id}", SingleWorkspaceDocumentApplication.Operation.GET_DOCUMENT, "GET");
    addDocumentRoute("/api/v2/documents/{id}", SingleWorkspaceDocumentApplication.Operation.UPDATE_DOCUMENT, "PATCH");
    addDocumentRoute("/api/v2/documents/{id}", SingleWorkspaceDocumentApplication.Operation.DELETE_DOCUMENT, "DELETE");
    addDocumentRoute("/api/v2/documents/{id}/revisions", SingleWorkspaceDocumentApplication.Operation.DOCUMENT_REVISIONS, "GET");
    addDocumentRoute("/api/v2/documents/{id}/revisions/{revision}/restore", SingleWorkspaceDocumentApplication.Operation.RESTORE_REVISION, "POST");
    addDocumentRoute("/api/v2/trash", SingleWorkspaceDocumentApplication.Operation.LIST_TRASH, "GET");
    addDocumentRoute("/api/v2/trash/{id}/restore", SingleWorkspaceDocumentApplication.Operation.RESTORE_TRASH, "POST");
    addDocumentRoute("/api/v2/ask", SingleWorkspaceDocumentApplication.Operation.ASK, "POST");
    addDocumentRoute("/api/v2/sessions", SingleWorkspaceDocumentApplication.Operation.LIST_SESSIONS, "GET");
    addDocumentRoute("/api/v2/sessions", SingleWorkspaceDocumentApplication.Operation.CREATE_SESSION, "POST");
    addDocumentRoute("/api/v2/sessions/{id}", SingleWorkspaceDocumentApplication.Operation.GET_SESSION, "GET");
    addDocumentRoute("/api/v2/sessions/{id}", SingleWorkspaceDocumentApplication.Operation.UPDATE_SESSION, "PATCH");
    addDocumentRoute("/api/v2/sessions/{id}", SingleWorkspaceDocumentApplication.Operation.DELETE_SESSION, "DELETE");
    addDocumentRoute("/api/v2/sessions/{id}/messages", SingleWorkspaceDocumentApplication.Operation.LIST_MESSAGES, "GET");
    addDocumentRoute("/api/v2/sessions/{id}/messages", SingleWorkspaceDocumentApplication.Operation.APPEND_MESSAGE, "POST");
    addDocumentRoute("/api/v2/sessions/{id}/summary", SingleWorkspaceDocumentApplication.Operation.GET_SUMMARY, "GET");
    addDocumentRoute("/api/v2/sessions/{id}/summary", SingleWorkspaceDocumentApplication.Operation.UPDATE_SUMMARY, "POST");
    addDocumentRoute("/api/v2/sessions/{id}/export", SingleWorkspaceDocumentApplication.Operation.EXPORT_SESSION, "GET");
    addDocumentRoute("/api/v2/sessions/import", SingleWorkspaceDocumentApplication.Operation.IMPORT_SESSION, "POST");
    addDocumentRoute("/api/v2/maintenance/cleanup", SingleWorkspaceDocumentApplication.Operation.CLEANUP, "POST");
    addDocumentRoute("/api/v2/maintenance/events", SingleWorkspaceDocumentApplication.Operation.RECENT_EVENTS, "GET");
  }

  private int root(Request request, Response response) throws IOException {
    if (!"/".equals(request.getPath())) {
      return 404;
    }
    response.getHeaders().add("Location", request.getBaseURL() + "/webui/index.html");
    response.send(302, "Temporarily moved to the WebUI.");
    return 0;
  }

  private int bundledWebUi(Request request, Response response) throws IOException {
    String requestPath = request.getPath();
    String relativePath =
        requestPath.startsWith("/webui/")
            ? requestPath.substring("/webui/".length())
            : "";
    if (relativePath.isBlank() || relativePath.contains("..") || relativePath.contains("\\")) {
      return 404;
    }
    try (InputStream input =
        SingleWorkspaceHttpApp.class
            .getClassLoader()
            .getResourceAsStream("webui/" + relativePath)) {
      if (input == null) {
        return 404;
      }
      byte[] bytes = input.readAllBytes();
      response.sendHeaders(
          200, bytes.length, -1, null, MimeTypeDetector.detect(relativePath), null);
      OutputStream output = response.getBody();
      if (output != null) {
        output.write(bytes);
      }
      return 0;
    }
  }

  private void addDocumentRoute(
      String path, SingleWorkspaceDocumentApplication.Operation operation, String method) {
    server.addRoute(
        path,
        guarded((request, response) -> document(request, response, operation)),
        method);
  }

  private void addVirtualProjectRoute(
      String path, VirtualProjectApplication.Operation operation, String method) {
    server.addRoute(
        path,
        guarded((request, response) -> virtualProject(request, response, operation)),
        method);
  }

  private ContextHandler guarded(ContextHandler handler) {
    return (request, response) -> {
      String obsolete = obsoleteWorkspaceField(request);
      if (obsolete != null) {
        Map<String, Object> payload =
            new LinkedHashMap<>(
                error("OBSOLETE_WORKSPACE_FIELD", "workspace routing is not supported"));
        payload.put("field", obsolete);
        return writeJson(response, 400, payload);
      }
      return handler.serve(request, response);
    };
  }

  private static String obsoleteWorkspaceField(Request request) {
    String field =
        obsoleteWorkspaceField(
            request.getParamMap().keySet(),
            request.getBodyMap().keySet(),
            request.getDataMap().keySet(),
            request.getHeaders().contains("X-Workspace-Id"),
            request.getHeaders().contains("X-Workspace"));
    return field;
  }

  static String obsoleteWorkspaceField(
      Set<String> queryFields,
      Set<String> formFields,
      Set<String> jsonFields,
      boolean workspaceIdHeader,
      boolean workspaceHeader) {
    for (String field : OBSOLETE_FIELDS) {
      if (queryFields.contains(field) || formFields.contains(field) || jsonFields.contains(field)) {
        return field;
      }
    }
    if (workspaceIdHeader) {
      return "X-Workspace-Id";
    }
    return workspaceHeader ? "X-Workspace" : null;
  }

  private int health(Request request, Response response) throws IOException {
    return writeJson(response, 200, Map.of("schemaVersion", "1", "alive", true, "state", readiness.get().state().name().toLowerCase()));
  }

  private int ready(Request request, Response response) throws IOException {
    ServiceReadiness value = readiness.get();
    return writeJson(response, value.ready() ? 200 : 503, readinessPayload(value));
  }

  private int status(Request request, Response response) throws IOException {
    return writeJson(response, 200, readinessPayload(readiness.get()));
  }

  private int knowledgeSearch(Request request, Response response) throws IOException {
    String query = parameter(request, "query");
    int limit = integer(parameter(request, "limit"), 20);
    return invoke(
        response,
        () ->
            application
                .agentFacade()
                .knowledgeSearch(
                    new AgentToolValues.KnowledgeSearchRequest(
                        SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                        query,
                        java.util.Set.of(),
                        limit,
                        false,
                        false,
                        parameter(request, "virtualProjectId"))));
  }

  private int knowledgeGet(Request request, Response response) throws IOException {
    return invoke(
        response,
        () ->
            application
                .agentFacade()
                .knowledgeGet(
                    new AgentToolValues.KnowledgeGetRequest(
                        SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                        parameter(request, "id"))));
  }

  private int trace(Request request, Response response) throws IOException {
    return invoke(
        response,
        () ->
            application
                .agentFacade()
                .trace(
                    new AgentToolValues.TraceRequest(
                        SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                        parameter(request, "id"))));
  }

  private int context(Request request, Response response) throws IOException {
    return invoke(
        response,
        () ->
            application
                .agentFacade()
                .buildContext(
                    new AgentToolValues.BuildContextRequest(
                        SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
                        parameter(request, "target"),
                        parameter(request, "intent"),
                        integer(parameter(request, "tokenBudget"), 2_000),
                        false,
                        false)));
  }

  private int document(
      Request request,
      Response response,
      SingleWorkspaceDocumentApplication.Operation operation)
      throws IOException {
    Map<String, Object> arguments = new LinkedHashMap<>(request.getDataMap());
    copyParameter(request, arguments, "id");
    copyParameter(request, arguments, "revision");
    copyParameter(request, arguments, "limit");
    copyParameter(request, arguments, "offset");
    copyParameter(request, arguments, "query");
    copyParameter(request, arguments, "root");
    copyParameter(request, arguments, "mode");
    return invoke(
        response,
        () ->
            application
                .documents()
                .execute(operation, Collections.unmodifiableMap(new LinkedHashMap<>(arguments))));
  }

  private int virtualProject(
      Request request, Response response, VirtualProjectApplication.Operation operation)
      throws IOException {
    Map<String, Object> arguments = new LinkedHashMap<>(request.getDataMap());
    copyParameter(request, arguments, "id");
    copyParameter(request, arguments, "referenceId");
    copyParameter(request, arguments, "limit");
    copyParameter(request, arguments, "sourcePath");
    copyParameter(request, arguments, "content");
    copyParameter(request, arguments, "oldText");
    copyParameter(request, arguments, "newText");
    if (arguments.containsKey("id")) {
      arguments.put("projectId", arguments.remove("id"));
    }
    return invoke(
        response,
        () ->
            application
                .virtualProjects()
                .execute(operation, Collections.unmodifiableMap(new LinkedHashMap<>(arguments))));
  }

  private static void copyParameter(
      Request request, Map<String, Object> arguments, String name) {
    String value = request.getParameter(name);
    if (value != null) {
      arguments.putIfAbsent(name, value);
    }
  }

  private int invoke(Response response, java.util.concurrent.Callable<Object> operation)
      throws IOException {
    try {
      Object result = application.operationCoordinator().execute(operation, REQUEST_DEADLINE);
      return writeJson(response, responseStatus(result), result);
    } catch (RejectedExecutionException unavailable) {
      return writeJson(response, 503, error("UNAVAILABLE", "service is not accepting work"));
    } catch (TimeoutException timeout) {
      return writeJson(response, 504, error("DEADLINE_EXCEEDED", "operation exceeded its deadline"));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return writeJson(response, 503, error("CANCELLED", "operation was interrupted"));
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof VirtualProjectApplication.NotFoundException) {
        return writeJson(response, 404, error("NOT_FOUND", bounded(cause.getMessage())));
      }
      if (cause instanceof VirtualProjectApplication.FileConflictException) {
        return writeJson(response, 409, error("FILE_CONFLICT", bounded(cause.getMessage())));
      }
      if (cause instanceof KnowledgeBase.DocumentSaveConflictException) {
        return writeJson(
            response, 409, error("DOCUMENT_CONFLICT", bounded(cause.getMessage())));
      }
      if (cause instanceof IllegalArgumentException) {
        return writeJson(response, 400, error("INVALID_ARGUMENT", bounded(cause.getMessage())));
      }
      if (cause instanceof UnsupportedOperationException) {
        return writeJson(response, 503, error("UNAVAILABLE", bounded(cause.getMessage())));
      }
      return writeJson(response, 500, error("INTERNAL", "operation failed"));
    }
  }

  private static String parameter(Request request, String name) {
    Object value = request.getDataMap().get(name);
    if (value != null) {
      return value.toString();
    }
    return request.getParameter(name);
  }

  private static int integer(String value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      return fallback;
    }
  }

  private static Map<String, Object> error(String code, String message) {
    return Map.of("schemaVersion", "1", "ok", false, "code", code, "message", message);
  }

  private static int responseStatus(Object result) {
    if (!(result instanceof AgentToolValues.ToolResponse<?> toolResponse)) {
      return 200;
    }
    return switch (toolResponse.status()) {
      case OK, INCOMPLETE -> 200;
      case INVALID_ARGUMENT -> 400;
      case NOT_FOUND -> 404;
      case UNAVAILABLE, CANCELLED -> 503;
      case DEADLINE_EXCEEDED -> 504;
      case INTERNAL -> 500;
    };
  }

  private static Map<String, Object> readinessPayload(ServiceReadiness readiness) {
    Map<String, Object> components = new LinkedHashMap<>();
    readiness.components().forEach(
        (name, state) -> components.put(name, state.name().toLowerCase(java.util.Locale.ROOT)));
    Map<String, Object> freshness = new LinkedHashMap<>();
    freshness.put("businessCheckpoint", readiness.freshness().businessCheckpoint());
    freshness.put("projectionCheckpoint", readiness.freshness().projectionCheckpoint());
    freshness.put(
        "lastSuccessfulProjectionCommitAt",
        readiness.freshness().lastSuccessfulProjectionCommitAt());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", readiness.schemaVersion());
    payload.put("ready", readiness.ready());
    payload.put("state", readiness.state().name().toLowerCase(java.util.Locale.ROOT));
    payload.put("reason", readiness.reason());
    payload.put("components", components);
    payload.put("queueDepth", readiness.queueDepth());
    payload.put("freshness", freshness);
    return payload;
  }

  private static String bounded(String message) {
    String safe = message == null ? "operation failed" : message;
    return safe.length() <= 256 ? safe : safe.substring(0, 256);
  }

  private static int writeJson(Response response, int status, Object payload) throws IOException {
    Object workspaceFree = workspaceFreePayload(payload);
    byte[] bytes =
        OKJSON.stringify(workspaceFree, JSON_OPTIONS)
            .getBytes(StandardCharsets.UTF_8);
    response.sendHeaders(status, bytes.length, -1, null, "application/json; charset=utf-8", null);
    OutputStream output = response.getBody();
    if (output != null) {
      output.write(bytes);
    }
    return 0;
  }

  static Object workspaceFreePayload(Object payload) {
    return removeWorkspaceRouting(transportValue(payload));
  }

  private static Object transportValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Optional<?> optional) {
      return optional.map(SingleWorkspaceHttpApp::transportValue).orElse(null);
    }
    if (value instanceof Instant instant) {
      return instant.toString();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach((key, nested) -> converted.put(String.valueOf(key), transportValue(nested)));
      return converted;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> converted = new ArrayList<>(collection.size());
      collection.forEach(item -> converted.add(transportValue(item)));
      return converted;
    }
    if (value.getClass().isRecord()) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (RecordComponent component : value.getClass().getRecordComponents()) {
        try {
          converted.put(component.getName(), transportValue(component.getAccessor().invoke(value)));
        } catch (IllegalAccessException | InvocationTargetException failure) {
          throw new IllegalStateException("response record could not be serialized", failure);
        }
      }
      return converted;
    }
    return value;
  }

  private static Object removeWorkspaceRouting(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> cleaned = new LinkedHashMap<>();
      map.forEach(
          (key, nested) -> {
            String name = String.valueOf(key);
            if (!OBSOLETE_FIELDS.contains(name)) {
              cleaned.put(name, removeWorkspaceRouting(nested));
            }
          });
      return cleaned;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> cleaned = new ArrayList<>(collection.size());
      collection.forEach(item -> cleaned.add(removeWorkspaceRouting(item)));
      return cleaned;
    }
    return value;
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;
    server.shutdown();
    composition.close();
  }
}
