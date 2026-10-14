package me.asu.kb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualProjectIndexManagerTest {
  @TempDir Path tempDirectory;

  @Test
  void mapsProjectIdsToServiceOwnedDirectoriesAndClosesIdleIndexes() throws Exception {
    Path root = tempDirectory.resolve("index");
    try (VirtualProjectIndexManager manager = new VirtualProjectIndexManager(root, 1)) {
      String projectId = "virtual-project:v1:project-a";

      Path directory = manager.indexDirectory(projectId);
      assertEquals(root.toAbsolutePath().normalize(), directory.getParent());
      assertFalse(directory.getFileName().toString().contains(":"));

      try (VirtualProjectIndexManager.ProjectIndexLease lease = manager.acquire(projectId)) {
        assertEquals(projectId, lease.projectId());
        assertEquals(directory, lease.directory());
        assertTrue(Files.isDirectory(directory));
        assertEquals(1, manager.openProjectIds().size());
      }

      manager.closeProject(projectId);
      assertTrue(Files.isDirectory(directory));
      assertTrue(manager.openProjectIds().isEmpty());
    }
  }

  @Test
  void doesNotEvictAnIndexWithAnActiveLease() throws Exception {
    try (VirtualProjectIndexManager manager =
            new VirtualProjectIndexManager(tempDirectory.resolve("index"), 1);
        VirtualProjectIndexManager.ProjectIndexLease ignored = manager.acquire("project-a")) {
      assertThrows(IllegalStateException.class, () -> manager.acquire("project-b"));
    }
  }
}
