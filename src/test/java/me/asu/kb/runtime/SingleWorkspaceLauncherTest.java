package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleWorkspaceLauncherTest {
  @TempDir Path temporaryDirectory;

  @Test
  void cliOverridesCanonicalTomlAndOptionalToolsAreExplicitlyUnavailable() throws Exception {
    Path fromToml = temporaryDirectory.resolve("toml-root");
    Path fromCli = temporaryDirectory.resolve("cli-root");
    Path config = temporaryDirectory.resolve("service.toml");
    Files.writeString(
        config,
        "workspace_root = \"" + fromToml.toString().replace("\\", "\\\\") + "\"\n"
            + "llm_provider = \"ollama\"\n");

    SingleWorkspaceLauncher.Configuration loaded =
        SingleWorkspaceLauncher.load(
            new String[] {
              "--config", config.toString(),
              "--workspace-root", fromCli.toString(),
              "--llm-provider", "echo",
              "--port", "9090"
            });

    assertEquals(fromCli.toAbsolutePath().normalize(), loaded.service().workspaceRoot());
    assertEquals("echo", loaded.llm().provider());
    assertEquals(9090, loaded.httpPort());
    assertTrue(loaded.tools().ripgrep().executable().isBlank());
    assertTrue(loaded.tools().ctags().executable().isBlank());
    assertTrue(loaded.tools().git().executable().isBlank());
    assertTrue(Files.notExists(fromToml));
    assertTrue(Files.notExists(fromCli));
  }

  @Test
  void rejectsRelativeToolExecutablesAndObsoleteWorkspaceKeysBeforeMutation() throws Exception {
    Path root = temporaryDirectory.resolve("workspace");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SingleWorkspaceLauncher.load(
                new String[] {
                  "--workspace-root", root.toString(), "--ctags-executable", "ctags"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SingleWorkspaceLauncher.load(
                new String[] {"--workspace-root", root.toString(), "--workspace", "legacy"}));
    assertTrue(Files.notExists(root));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SingleWorkspaceLauncher.load(
                new String[] {"--workspace-root", root.toString(), "--port", "70000"}));
  }
}
