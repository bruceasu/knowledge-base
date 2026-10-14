package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperation;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperationOutcome;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperationResult;
import me.asu.kb.runtime.VirtualProjectApplication.ProjectView;
import me.asu.kb.service.VirtualProjectPathPolicy;
import me.asu.kb.store.SqliteKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualProjectApplicationTest {
  @TempDir Path tempDir;

  @Test
  void fileOperationsStayWithinProjectMembershipAndReportSearchRefreshRequirement()
      throws Exception {
    Path projectRoot = Files.createDirectory(tempDir.resolve("project"));
    Path target = projectRoot.resolve("memory.md");
    Path outside = Files.createFile(tempDir.resolve("outside.md"));

    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve("knowledge.sqlite"))) {
      SqliteVirtualProjectApplication application =
          new SqliteVirtualProjectApplication(
              store, new VirtualProjectPathPolicy(tempDir));
      ProjectView project =
          (ProjectView)
              application.execute(
                  VirtualProjectApplication.Operation.CREATE_PROJECT,
                  Map.of("name", "Agent Memory", "description", "ordinary files"));
      String projectId = project.project().id();
      application.execute(
          VirtualProjectApplication.Operation.ADD_RESOURCE,
          Map.of(
              "projectId", projectId,
              "kind", "DIRECTORY",
              "sourcePath", projectRoot.toString()));

      FileOperationResult created =
          executeFile(
              application,
              VirtualProjectApplication.Operation.CREATE_FILE,
              projectId,
              target,
              "one");
      assertEquals(FileOperation.CREATE, created.operation());
      assertEquals(FileOperationOutcome.SUCCEEDED, created.outcome());
      assertTrue(created.changed());
      assertTrue(created.requiresSynchronization());

      executeFile(application, VirtualProjectApplication.Operation.APPEND_FILE, projectId, target, " two");
      executeFile(application, VirtualProjectApplication.Operation.REPLACE_FILE, projectId, target, "two");
      application.execute(
          VirtualProjectApplication.Operation.EDIT_FILE,
          Map.of(
              "projectId", projectId,
              "sourcePath", target.toString(),
              "oldText", "two",
              "newText", "three"));
      assertEquals("three", Files.readString(target));

      assertThrows(
          VirtualProjectApplication.FileOperationException.class,
          () ->
              executeFile(
                  application,
                  VirtualProjectApplication.Operation.REPLACE_FILE,
                  projectId,
                  outside,
                  "must not write"));
      assertEquals("", Files.readString(outside));

      FileOperationResult deleted =
          (FileOperationResult)
              application.execute(
                  VirtualProjectApplication.Operation.DELETE_FILE,
                  Map.of("projectId", projectId, "sourcePath", target.toString()));
      assertEquals(FileOperation.DELETE, deleted.operation());
      assertFalse(Files.exists(target));
    }
  }

  private static FileOperationResult executeFile(
      SqliteVirtualProjectApplication application,
      VirtualProjectApplication.Operation operation,
      String projectId,
      Path target,
      String content)
      throws Exception {
    return (FileOperationResult)
        application.execute(
            operation,
            Map.of("projectId", projectId, "sourcePath", target.toString(), "content", content));
  }
}
