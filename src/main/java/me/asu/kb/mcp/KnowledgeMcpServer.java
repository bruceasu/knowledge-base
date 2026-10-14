package me.asu.kb.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.agent.AgentCallDeadline;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Local-stdio-only MCP lifecycle for the fixed engineering-knowledge tool set. */
public final class KnowledgeMcpServer implements AutoCloseable {
  public static final String SERVER_NAME = "engineering-knowledge";
  public static final String SERVER_VERSION = "1.0";

  private final McpAsyncServer server;
  private final Duration requestTimeout;

  private KnowledgeMcpServer(McpAsyncServer server, Duration requestTimeout) {
    this.server = server;
    this.requestTimeout = requestTimeout;
  }

  public static KnowledgeMcpServer start(
      InputStream input, OutputStream protocolOutput, KnowledgeMcpToolHandler handler) {
    return start(input, protocolOutput, handler, Duration.ofSeconds(10));
  }

  static KnowledgeMcpServer start(
      InputStream input,
      OutputStream protocolOutput,
      KnowledgeMcpToolHandler handler,
      Duration requestTimeout) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(protocolOutput, "protocolOutput");
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(requestTimeout, "requestTimeout");

    configureSdkDiagnostics();
    McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
    StdioServerTransportProvider transport =
        new StdioServerTransportProvider(mapper, input, protocolOutput, AgentProtocolLimits.REQUEST_BYTES);
    List<McpServerFeatures.AsyncToolSpecification> tools =
        KnowledgeAgentToolSchemas.all().values().stream()
            .map(schema -> toolSpecification(schema, handler, requestTimeout))
            .toList();
    McpAsyncServer server =
        McpServer.async(transport)
            .serverInfo(SERVER_NAME, SERVER_VERSION)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .requestTimeout(requestTimeout)
            .strictToolNameValidation(true)
            .validateToolInputs(true)
            .tools(tools)
            .build();
    return new KnowledgeMcpServer(server, requestTimeout);
  }

  private static void configureSdkDiagnostics() {
    if (System.getProperty("slf4j.internal.verbosity") == null) {
      System.setProperty("slf4j.internal.verbosity", "ERROR");
    }
  }

  public List<String> toolNames() {
    return server.listTools().map(McpSchema.Tool::name).sort().collectList().block(requestTimeout);
  }

  @Override
  public void close() {
    server.closeGracefully().block(requestTimeout);
  }

  private static McpServerFeatures.AsyncToolSpecification toolSpecification(
      KnowledgeAgentToolSchemas.ToolSchema schema,
      KnowledgeMcpToolHandler handler,
      Duration requestTimeout) {
    McpSchema.Tool tool =
        McpSchema.Tool.builder(schema.name())
            .description(KnowledgeAgentToolSchemas.description(schema.name()))
            .inputSchema(schema.input())
            .outputSchema(schema.output())
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(true)
                    .destructiveHint(false)
                    .idempotentHint(true)
                    .openWorldHint(false)
                    .build())
            .meta(Map.of("schemaVersion", schema.schemaVersion()))
            .build();
    return new McpServerFeatures.AsyncToolSpecification(
        tool,
        (exchange, request) ->
            Mono.fromCallable(() -> handler.call(schema.name(), request.arguments()))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(requestTimeout.compareTo(AgentCallDeadline.MAXIMUM_DURATION) < 0
                    ? requestTimeout
                    : AgentCallDeadline.MAXIMUM_DURATION));
  }

  private static final class AgentProtocolLimits {
    private static final int REQUEST_BYTES = 65_536;
  }
}
