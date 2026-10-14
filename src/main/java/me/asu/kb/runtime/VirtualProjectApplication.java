package me.asu.kb.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProject;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectIndexStatus;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceReference;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectSyncStatus;

/** Transport-independent application boundary for virtual-project metadata operations. */
public interface VirtualProjectApplication {
  Object execute(Operation operation, Map<String, Object> arguments) throws Exception;

  enum Operation {
    LIST_PROJECTS,
    CREATE_PROJECT,
    GET_PROJECT,
    UPDATE_PROJECT,
    DELETE_PROJECT,
    GET_PROJECT_STATUS,
    SYNCHRONIZE_PROJECT,
    LIST_RESOURCES,
    ADD_RESOURCE,
    REMOVE_RESOURCE,
    CREATE_FILE,
    APPEND_FILE,
    REPLACE_FILE,
    EDIT_FILE,
    DELETE_FILE
  }

  record ProjectView(VirtualProject project, List<VirtualProjectResourceReference> resources) {
    public ProjectView {
      Objects.requireNonNull(project, "project");
      resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
    }
  }

  /** Read-only project index and synchronization lifecycle status. */
  record ProjectStatus(
      String projectId,
      VirtualProjectIndexStatus indexStatus,
      VirtualProjectSyncStatus syncStatus,
      Optional<String> lastSyncStartedAt,
      Optional<String> lastSyncCompletedAt,
      Optional<String> lastSyncDiagnostic) {
    public ProjectStatus {
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalArgumentException("projectId must not be blank");
      }
      Objects.requireNonNull(indexStatus, "indexStatus");
      Objects.requireNonNull(syncStatus, "syncStatus");
      Objects.requireNonNull(lastSyncStartedAt, "lastSyncStartedAt");
      Objects.requireNonNull(lastSyncCompletedAt, "lastSyncCompletedAt");
      Objects.requireNonNull(lastSyncDiagnostic, "lastSyncDiagnostic");
    }
  }

  record ProjectList(List<VirtualProject> projects) {
    public ProjectList {
      projects = List.copyOf(Objects.requireNonNull(projects, "projects"));
    }
  }

  record ResourceList(String projectId, List<VirtualProjectResourceReference> resources) {
    public ResourceList {
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalArgumentException("projectId must not be blank");
      }
      resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
    }
  }

  record DeletionResult(String projectId, String referenceId, boolean deleted) {
    public DeletionResult {
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalArgumentException("projectId must not be blank");
      }
      if (referenceId != null && referenceId.isBlank()) {
        throw new IllegalArgumentException("referenceId must not be blank");
      }
    }
  }

  record FileOperationResult(
      String projectId,
      FileOperation operation,
      String sourcePath,
      FileOperationOutcome outcome,
      boolean changed,
      long sizeBytes,
      boolean requiresSynchronization) {
    public FileOperationResult {
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalArgumentException("projectId must not be blank");
      }
      Objects.requireNonNull(operation, "operation");
      if (sourcePath == null || sourcePath.isBlank()) {
        throw new IllegalArgumentException("sourcePath must not be blank");
      }
      Objects.requireNonNull(outcome, "outcome");
      if (sizeBytes < 0) {
        throw new IllegalArgumentException("sizeBytes must not be negative");
      }
    }
  }

  enum FileOperation {
    CREATE,
    APPEND,
    REPLACE,
    EDIT,
    DELETE
  }

  enum FileOperationOutcome {
    SUCCEEDED
  }

  /** Sanitized not-found error mapped to HTTP 404 by the transport. */
  final class NotFoundException extends IllegalArgumentException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /** Sanitized conflict returned when a file operation cannot safely apply. */
  final class FileConflictException extends IllegalArgumentException {
    public FileConflictException(String message) {
      super(message);
    }
  }

  /** Sanitized file-operation failure that does not expose local filesystem details. */
  final class FileOperationException extends IllegalArgumentException {
    public FileOperationException(String message) {
      super(message);
    }
  }
}
