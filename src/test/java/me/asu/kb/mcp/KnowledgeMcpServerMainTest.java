package me.asu.kb.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeMcpServerMainTest {
  @TempDir Path temporaryDirectory;

  @Test
  void startsAllToolsFromTheSharedProductionComposition() throws Exception {
    Path workspace = temporaryDirectory.resolve("workspace");
    ByteArrayOutputStream protocolOutput = new ByteArrayOutputStream();

    try (KnowledgeMcpServerMain.RunningServer running =
        KnowledgeMcpServerMain.start(
            new String[] {
              "--workspace-root", workspace.toString(), "--llm-provider", "echo"
            },
            new ByteArrayInputStream(new byte[0]),
            protocolOutput)) {
      assertEquals(
          List.of(
              "build_context",
              "code_search",
              "code_symbol",
              "knowledge_get",
              "knowledge_search",
              "trace"),
          running.toolNames());
      assertTrue(Files.isRegularFile(workspace.resolve(".engineering-knowledge-workspace.json")));
      assertTrue(protocolOutput.toString().isEmpty());
    }
  }
}
