package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleWorkspaceServiceConfigTest {
  @TempDir Path tempDir;

  @Test
  void createsOneNormalizedInternalWorkspaceFromCli() {
    Path configured = tempDir.resolve("nested").resolve("..").resolve("workspace");

    SingleWorkspaceServiceConfig config =
        SingleWorkspaceServiceConfig.parse(
            new String[] {"--port", "8080", "--workspace-root", configured.toString()}, Map.of());

    Path normalized = configured.toAbsolutePath().normalize();
    assertEquals(normalized, config.workspaceRoot());
    assertEquals(SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID, config.workspace().id());
    assertEquals(normalized, config.workspace().root());
    assertEquals(config.workspace(), config.workspaceCatalog().find("service").orElseThrow());
    assertEquals(1, config.workspaceCatalog().workspaces().size());
    assertEquals(Map.of("service", normalized), config.workspaceRoots());
    assertThrows(UnsupportedOperationException.class, () -> config.workspaceRoots().put("other", tempDir));
  }

  @Test
  void acceptsTheCanonicalTomlKeyWithoutTouchingTheFilesystem() {
    Path absent = tempDir.resolve("not-created");

    SingleWorkspaceServiceConfig config =
        SingleWorkspaceServiceConfig.parse(new String[0], Map.of("workspace_root", absent.toString()));

    assertEquals(absent.toAbsolutePath().normalize(), config.workspaceRoot());
    assertTrue(java.nio.file.Files.notExists(absent));
  }

  @Test
  void rejectsMissingRepeatedOrMixedRoots() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SingleWorkspaceServiceConfig.parse(new String[0], Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SingleWorkspaceServiceConfig.parse(
                new String[] {"--workspace-root", "one", "--workspace-root", "two"}, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SingleWorkspaceServiceConfig.parse(
                new String[] {"--workspace-root", "one"}, Map.of("workspace_root", "two")));
  }

  @Test
  void rejectsEveryLegacyWorkspaceConfigurationBeforeSelection() {
    for (String obsolete :
        new String[] {"--workspace", "--workspace-id", "--server-workspace", "--server-root"}) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  SingleWorkspaceServiceConfig.parse(
                      new String[] {"--workspace-root", "new", obsolete, "legacy"}, Map.of()));
      assertTrue(error.getMessage().contains(obsolete));
    }
    for (String obsolete :
        new String[] {
          "workspace", "workspace_id", "workspaces", "server_workspace", "server_root", "server_roots"
        }) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  SingleWorkspaceServiceConfig.parse(
                      new String[] {"--workspace-root", "new"}, Map.of(obsolete, "legacy")));
      assertTrue(error.getMessage().contains(obsolete));
    }
  }

  @Test
  void rejectsDerivedPathsThatCanEscapeTheConfiguredRoot() {
    SingleWorkspaceServiceConfig config =
        SingleWorkspaceServiceConfig.parse(
            new String[] {"--workspace-root", tempDir.toString()}, Map.of());

    assertEquals(tempDir.resolve("state/knowledge.sqlite"),
        config.resolveInsideWorkspace(Path.of("state/knowledge.sqlite")));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.resolveInsideWorkspace(Path.of("..").resolve("outside")));
    assertThrows(
        IllegalArgumentException.class,
        () -> config.resolveInsideWorkspace(tempDir.resolve("absolute")));
  }
}
