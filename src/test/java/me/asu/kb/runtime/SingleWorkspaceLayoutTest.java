package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleWorkspaceLayoutTest {
  @TempDir Path tempDir;

  @Test
  void initializesAnAbsentRootAndOpensItRepeatably() throws Exception {
    Path root = tempDir.resolve("new-workspace");
    SingleWorkspaceServiceConfig config = config(root);

    SingleWorkspaceLayout.OpenedWorkspace initialized = SingleWorkspaceLayout.open(config);
    byte[] markerBytes = Files.readAllBytes(initialized.marker());
    SingleWorkspaceLayout.OpenedWorkspace reopened = SingleWorkspaceLayout.open(config);

    assertTrue(initialized.initialized());
    assertFalse(reopened.initialized());
    assertEquals(root.toAbsolutePath().normalize(), reopened.root());
    assertArrayEquals(markerBytes, Files.readAllBytes(reopened.marker()));
  }

  @Test
  void initializesAnExistingEmptyRoot() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("empty"));

    SingleWorkspaceLayout.OpenedWorkspace opened = SingleWorkspaceLayout.open(config(root));

    assertTrue(opened.initialized());
    assertTrue(Files.isRegularFile(root.resolve(SingleWorkspaceLayout.MARKER_FILE_NAME)));
  }

  @Test
  void rejectsUnmarkedExistingStateWithoutMutation() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("legacy"));
    Path legacy = Files.writeString(root.resolve("knowledge.db"), "legacy");
    byte[] before = Files.readAllBytes(legacy);

    IOException error =
        assertThrows(IOException.class, () -> SingleWorkspaceLayout.open(config(root)));

    assertTrue(error.getMessage().contains("fresh workspace"));
    assertArrayEquals(before, Files.readAllBytes(legacy));
    assertFalse(Files.exists(root.resolve(SingleWorkspaceLayout.MARKER_FILE_NAME)));
    assertEquals(1, countEntries(root));
  }

  @Test
  void rejectsMalformedOrIncompatibleMarkersWithoutMutation() throws Exception {
    assertRejectedUnchanged("malformed", "not-json");
    assertRejectedUnchanged(
        "trailing",
        "{\"format\":\"engineering-knowledge-single-workspace\",\"version\":1} trailing");
    assertRejectedUnchanged(
        "format", "{\"format\":\"other\",\"version\":1}");
    assertRejectedUnchanged(
        "version", "{\"format\":\"engineering-knowledge-single-workspace\",\"version\":2}");
    assertRejectedUnchanged(
        "decimal", "{\"format\":\"engineering-knowledge-single-workspace\",\"version\":1.0}");
    assertRejectedUnchanged(
        "additional",
        "{\"format\":\"engineering-knowledge-single-workspace\",\"version\":1,\"extra\":true}");
  }

  private void assertRejectedUnchanged(String name, String markerContent) throws Exception {
    Path root = Files.createDirectory(tempDir.resolve(name));
    Path marker =
        Files.writeString(
            root.resolve(SingleWorkspaceLayout.MARKER_FILE_NAME),
            markerContent,
            StandardCharsets.UTF_8);
    byte[] before = Files.readAllBytes(marker);

    assertThrows(IOException.class, () -> SingleWorkspaceLayout.open(config(root)));

    assertArrayEquals(before, Files.readAllBytes(marker));
    assertEquals(1, countEntries(root));
  }

  private static SingleWorkspaceServiceConfig config(Path root) {
    return SingleWorkspaceServiceConfig.parse(
        new String[] {"--workspace-root", root.toString()}, Map.of());
  }

  private static long countEntries(Path root) throws IOException {
    try (var entries = Files.list(root)) {
      return entries.count();
    }
  }
}
