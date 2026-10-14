package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LegacySurfaceAbsenceContractTest {
  private static final List<String> REMOVED_TYPES =
      List.of(
          "KnowledgeBaseCli",
          "KnowledgeBaseHttpClient",
          "KnowledgeBaseConfig",
          "KnowledgeBaseHttpApp",
          "KnowledgeBaseHttpService",
          "WorkspaceRegistry",
          "WorkspaceContext",
          "KnowledgeBaseEventBusBridge",
          "TomlConfigLoader",
          "EnvLoader");

  private static final Pattern LEGACY_DOCUMENTED_COMMAND =
      Pattern.compile(
          "(?im)^\\s*(?:java\\b[^\\r\\n]*KnowledgeBaseCli|curl\\b[^\\r\\n]*/api/v1)");

  @Test
  void activeProductionSurfacesContainNoLegacyRuntime() throws IOException {
    String production =
        readTrees(
            Path.of("src/main/java"),
            Path.of("src/main/resources"),
            Path.of("webui"));

    assertFalse(production.contains("/api/v1"), "legacy API route remains active");
    assertFalse(production.contains("registerWorkspace"), "workspace registration remains active");
    for (String removedType : REMOVED_TYPES) {
      assertFalse(production.contains(removedType), "removed type remains active: " + removedType);
    }
  }

  @Test
  void packagingAndActiveDocumentationExposeOnlySupportedLaunchers() throws IOException {
    String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
    assertTrue(pom.contains("<mainClass>me.asu.kb.http.SingleWorkspaceHttpServerMain</mainClass>"));
    assertFalse(pom.contains("KnowledgeBaseCli"));

    String activeDocumentation =
        Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
            + Files.readString(Path.of("README.zh.md"), StandardCharsets.UTF_8)
            + readTrees(Path.of("docs"));
    assertFalse(
        LEGACY_DOCUMENTED_COMMAND.matcher(activeDocumentation).find(),
        "active documentation advertises a legacy command or route");
  }

  @Test
  void obsoleteWorkspaceOptionsExistOnlyAsFatalInputGuards() throws IOException {
    String config =
        Files.readString(
            Path.of("src/main/java/me/asu/kb/runtime/SingleWorkspaceServiceConfig.java"),
            StandardCharsets.UTF_8);
    for (String obsolete :
        List.of(
            "--workspace",
            "--workspace-id",
            "--server-workspace",
            "--server-root",
            "server_roots")) {
      assertTrue(config.contains(obsolete), "missing fatal guard for " + obsolete);
    }
    assertFalse(config.contains("WorkspaceRegistry"));
    assertFalse(config.contains("registerWorkspace"));
  }

  private static String readTrees(Path... roots) throws IOException {
    StringBuilder content = new StringBuilder();
    for (Path root : roots) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(root)) {
        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
          content.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
        }
      }
    }
    return content.toString();
  }
}
