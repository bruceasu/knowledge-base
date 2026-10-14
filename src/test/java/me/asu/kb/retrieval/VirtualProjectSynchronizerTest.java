package me.asu.kb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import me.asu.kb.runtime.VirtualProjectSynchronizer;
import me.asu.kb.service.VirtualProjectPathPolicy;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualProjectSynchronizerTest {
  @TempDir Path tempDir;

  @Test
  void rebuildsTheProjectIndexAndRemovesDeletedSourcesOnTheNextSync() throws Exception {
    Path sourceRoot = Files.createDirectory(tempDir.resolve("sources"));
    Path current = Files.writeString(sourceRoot.resolve("current.md"), "keep this content");
    Path removed = Files.writeString(sourceRoot.resolve("removed.md"), "remove this content");
    Files.writeString(sourceRoot.resolve("image.bin"), "unsupported");

    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve("knowledge.sqlite"));
        VirtualProjectIndexManager indexManager =
            new VirtualProjectIndexManager(tempDir.resolve("indexes"))) {
      String projectId = store.createVirtualProject("Project", "sync test").id();
      store.addVirtualProjectResource(projectId, VirtualProjectResourceKind.DIRECTORY, sourceRoot.toString());
      VirtualProjectSynchronizer synchronizer =
          new VirtualProjectSynchronizer(
              store, new VirtualProjectPathPolicy(tempDir), indexManager);

      VirtualProjectSynchronizer.SyncReport first = synchronizer.synchronize(projectId);
      assertEquals(VirtualProjectSynchronizer.SyncOutcome.PARTIAL, first.outcome());
      assertEquals(2, first.indexedFiles());
      assertEquals(3, first.discoveredFiles());
      assertEquals(1, first.unsupportedFiles());
      assertTrue(search(store, indexManager, projectId, "remove").candidates().stream()
          .anyMatch(candidate -> candidate.source().locator().equals(removed.toString())));

      Files.delete(removed);
      VirtualProjectSynchronizer.SyncReport second = synchronizer.synchronize(projectId);
      assertEquals(VirtualProjectSynchronizer.SyncOutcome.PARTIAL, second.outcome());
      assertEquals(1, second.indexedFiles());
      assertEquals(2, second.discoveredFiles());
      assertTrue(search(store, indexManager, projectId, "remove").candidates().isEmpty());
      assertTrue(search(store, indexManager, projectId, "keep").candidates().stream()
          .anyMatch(candidate -> candidate.source().locator().equals(current.toString())));
    }
  }

  private static CandidateBatch search(
      SqliteKnowledgeStore store,
      VirtualProjectIndexManager indexManager,
      String projectId,
      String text) {
    ValidatedKnowledgeQuery query =
        new ValidatedKnowledgeQuery(
            projectId,
            Path.of(".").toAbsolutePath().normalize(),
            text,
            Set.of(),
            false,
            false,
            RetrievalBudgets.forResultLimit(10),
            projectId);
    return new VirtualProjectLuceneCandidateSource(store, indexManager).collect(query);
  }
}
