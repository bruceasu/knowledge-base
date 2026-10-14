package me.asu.kb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeMcpServerTest {
  @Test
  void registersOnlyTheFixedToolsOnTheStdioLifecycle() {
    ByteArrayOutputStream protocolOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnosticOutput = new ByteArrayOutputStream();
    PrintStream originalError = System.err;
    System.setErr(new PrintStream(diagnosticOutput));

    try (KnowledgeMcpServer server =
        KnowledgeMcpServer.start(
            new ByteArrayInputStream(new byte[0]),
            protocolOutput,
            (name, arguments) ->
                McpSchema.CallToolResult.builder().textContent(List.of("unused")).build())) {
      assertEquals(
          List.of(
              "build_context",
              "code_search",
              "code_symbol",
              "knowledge_get",
              "knowledge_search",
              "trace"),
          server.toolNames());
      assertTrue(protocolOutput.toString().isEmpty());
      assertTrue(diagnosticOutput.toString().isEmpty());
    } finally {
      System.setErr(originalError);
    }
  }
}
