package me.asu.kb.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.VirtualProjectIndexManager;
import me.asu.kb.service.VirtualProjectPathPolicy;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectLifecycleState;
import me.asu.kb.store.SqliteKnowledgeStore.StoredObject;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProject;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectIndexStatus;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceKind;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceReference;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectSyncStatus;

/** Rebuilds one virtual-project index from its current, policy-approved source membership. */
public final class VirtualProjectSynchronizer {
  private static final int MAX_FILES = 2_048;
  private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
  private static final String SOURCE_SYSTEM = "virtual-project";

  private static final List<String> SUPPORTED_SUFFIXES =
      List.of(
          ".adoc",
          ".bat",
          ".c",
          ".cpp",
          ".cs",
          ".css",
          ".gradle",
          ".h",
          ".hpp",
          ".java",
          ".js",
          ".json",
          ".jsx",
          ".kt",
          ".kts",
          ".markdown",
          ".md",
          ".org",
          ".properties",
          ".ps1",
          ".py",
          ".rs",
          ".rst",
          ".sh",
          ".sql",
          ".ts",
          ".tsx",
          ".txt",
          ".xml",
          ".yaml",
          ".yml",
          ".toml");

  private final SqliteKnowledgeStore store;
  private final VirtualProjectPathPolicy pathPolicy;
  private final VirtualProjectIndexManager indexManager;
  private final VirtualProjectOperationCoordinator operationCoordinator;

  public VirtualProjectSynchronizer(
      SqliteKnowledgeStore store,
      VirtualProjectPathPolicy pathPolicy,
      VirtualProjectIndexManager indexManager) {
    this(store, pathPolicy, indexManager, new VirtualProjectOperationCoordinator());
  }

  public VirtualProjectSynchronizer(
      SqliteKnowledgeStore store,
      VirtualProjectPathPolicy pathPolicy,
      VirtualProjectIndexManager indexManager,
      VirtualProjectOperationCoordinator operationCoordinator) {
    this.store = Objects.requireNonNull(store, "store");
    this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    this.indexManager = Objects.requireNonNull(indexManager, "indexManager");
    this.operationCoordinator =
        Objects.requireNonNull(operationCoordinator, "operationCoordinator");
  }

  public VirtualProjectOperationCoordinator operationCoordinator() {
    return operationCoordinator;
  }

  /**
   * Synchronizes one project by replacing its derived index with the current source snapshot.
   * Repeating the operation with unchanged files produces the same object IDs and index content.
   */
  public SyncReport synchronize(String projectId) throws SQLException {
    VirtualProject project =
        store.findVirtualProject(projectId).orElseThrow(() -> unknownProject(projectId));
    VirtualProjectOperationCoordinator.ProjectLock projectLock =
        operationCoordinator.tryAcquire(project.id());
    if (projectLock == null) {
      return new SyncReport(
          project.id(),
          0,
          0,
          0,
          0,
          SyncOutcome.SKIPPED,
          List.of());
    }
    try {
      return synchronizeLocked(project);
    } finally {
      projectLock.close();
    }
  }

  private SyncReport synchronizeLocked(VirtualProject project) throws SQLException {
    String startedAt = Instant.now().toString();
    store.updateVirtualProjectState(
        project.id(),
        project.indexStatus(),
        VirtualProjectSyncStatus.RUNNING,
        Optional.of(startedAt),
        project.lastSyncCompletedAt(),
        Optional.empty());

    List<SourceDiagnostic> diagnostics = new ArrayList<>();
    Map<String, Path> files = expand(project.id(), diagnostics);
    List<StoredObject> objects = new ArrayList<>();
    int unsupported = 0;
    int failed = 0;
    for (Path path : files.values()) {
      if (!supported(path)) {
        unsupported++;
        diagnostics.add(new SourceDiagnostic(path.toString(), SourceStatus.UNSUPPORTED, "file type is not supported"));
        continue;
      }
      try {
        objects.add(toStoredObject(project.id(), path));
      } catch (IOException | RuntimeException error) {
        failed++;
        diagnostics.add(new SourceDiagnostic(path.toString(), SourceStatus.FAILED, "source could not be read"));
      }
    }

    String completedAt = Instant.now().toString();
    try (VirtualProjectIndexManager.ProjectIndexLease lease = indexManager.acquire(project.id())) {
      lease.rebuild(project.id(), objects);
    } catch (IOException | RuntimeException error) {
      String detail = "project index could not be rebuilt";
      diagnostics.add(new SourceDiagnostic(project.id(), SourceStatus.FAILED, detail));
      VirtualProject failedProject =
          store.updateVirtualProjectState(
              project.id(),
              VirtualProjectIndexStatus.FAILED,
              VirtualProjectSyncStatus.FAILED,
              Optional.of(startedAt),
              Optional.of(completedAt),
              Optional.of(detail));
      return new SyncReport(
          failedProject.id(), 0, files.size(), unsupported, failed + 1, SyncOutcome.FAILED, diagnostics);
    }

    SyncOutcome outcome = diagnostics.isEmpty() ? SyncOutcome.SUCCEEDED : SyncOutcome.PARTIAL;
    VirtualProjectSyncStatus syncStatus =
        outcome == SyncOutcome.SUCCEEDED
            ? VirtualProjectSyncStatus.SUCCEEDED
            : VirtualProjectSyncStatus.PARTIAL;
    String diagnostic = diagnostics.isEmpty() ? null : summarize(diagnostics);
    VirtualProject completed =
        store.updateVirtualProjectState(
            project.id(),
            VirtualProjectIndexStatus.CURRENT,
            syncStatus,
            Optional.of(startedAt),
            Optional.of(completedAt),
            Optional.ofNullable(diagnostic));
    return new SyncReport(
        completed.id(),
        objects.size(),
        files.size(),
        unsupported,
        failed,
        outcome,
        diagnostics);
  }

  private Map<String, Path> expand(String projectId, List<SourceDiagnostic> diagnostics)
      throws SQLException {
    List<VirtualProjectResourceReference> references =
        store.findVirtualProjectResources(projectId, 1_000);
    Map<String, Path> files = new TreeMap<>();
    for (VirtualProjectResourceReference reference : references) {
      Path requested;
      try {
        requested = Path.of(reference.sourcePath());
      } catch (RuntimeException error) {
        diagnostics.add(
            new SourceDiagnostic(reference.sourcePath(), SourceStatus.FAILED, "resource path is invalid"));
        continue;
      }
      final Path canonical;
      try {
        canonical = pathPolicy.requireAllowed(requested);
      } catch (VirtualProjectPathPolicy.PathPolicyViolationException denied) {
        diagnostics.add(
            new SourceDiagnostic(reference.sourcePath(), SourceStatus.FAILED, denied.getMessage()));
        continue;
      }
      if (reference.kind() == VirtualProjectResourceKind.FILE) {
        addFile(canonical, files, diagnostics);
        continue;
      }
      expandDirectory(canonical, files, diagnostics);
    }
    return files;
  }

  private void expandDirectory(Path directory, Map<String, Path> files, List<SourceDiagnostic> diagnostics) {
    if (!Files.isDirectory(directory)) {
      diagnostics.add(new SourceDiagnostic(directory.toString(), SourceStatus.FAILED, "directory is unavailable"));
      return;
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(Comparator.comparing(Path::toString))
          .filter(path -> Files.isRegularFile(path))
          .forEach(path -> addFile(path, files, diagnostics));
    } catch (IOException | RuntimeException error) {
      diagnostics.add(new SourceDiagnostic(directory.toString(), SourceStatus.FAILED, "directory could not be scanned"));
    }
  }

  private void addFile(Path requested, Map<String, Path> files, List<SourceDiagnostic> diagnostics) {
    if (files.size() >= MAX_FILES) {
      diagnostics.add(new SourceDiagnostic(requested.toString(), SourceStatus.FAILED, "project file limit exceeded"));
      return;
    }
    final Path canonical;
    try {
      canonical = pathPolicy.requireAllowed(requested);
    } catch (VirtualProjectPathPolicy.PathPolicyViolationException denied) {
      diagnostics.add(new SourceDiagnostic(requested.toString(), SourceStatus.FAILED, denied.getMessage()));
      return;
    }
    if (!Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
      diagnostics.add(new SourceDiagnostic(canonical.toString(), SourceStatus.FAILED, "file is unavailable"));
      return;
    }
    files.putIfAbsent(canonical.toString(), canonical);
  }

  private static boolean supported(Path path) {
    String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
    return SUPPORTED_SUFFIXES.stream().anyMatch(name::endsWith);
  }

  private static StoredObject toStoredObject(String projectId, Path path) throws IOException {
    if (Files.size(path) > MAX_FILE_BYTES) {
      throw new IOException("source exceeds the project file limit");
    }
    byte[] bytes = Files.readAllBytes(path);
    String content = decodeUtf8(bytes);
    if (content.indexOf('\0') >= 0) {
      throw new IOException("source is not text");
    }
    String sourcePath = path.toString();
    String hash = sha256(bytes);
    String objectId = "virtual-project-file:v1:" + sha256(projectId + "\n" + sourcePath);
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("virtual-project.id", projectId);
    metadata.put("source.path", sourcePath);
    metadata.put("source.kind", "FILE");
    KnowledgeObject object =
        KnowledgeObject.canonical(
            objectId,
            ObjectType.OTHER,
            path.getFileName().toString(),
            Optional.of(content),
            SourceRef.of(SOURCE_SYSTEM, sourcePath),
            metadata,
            hash);
    return new StoredObject(object, Optional.empty(), ObjectLifecycleState.CURRENT);
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static String summarize(List<SourceDiagnostic> diagnostics) {
    StringBuilder result = new StringBuilder();
    for (SourceDiagnostic diagnostic : diagnostics) {
      if (result.length() > 0) {
        result.append("; ");
      }
      result.append(diagnostic.status().name().toLowerCase()).append(": ").append(diagnostic.detail());
      if (result.length() >= 1_024) {
        return result.substring(0, 1_024);
      }
    }
    return result.toString();
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JRE is missing SHA-256", error);
    }
  }

  private static VirtualProjectApplication.NotFoundException unknownProject(String projectId) {
    return new VirtualProjectApplication.NotFoundException("unknown virtual project: " + projectId);
  }

  public record SyncReport(
      String projectId,
      int indexedFiles,
      int discoveredFiles,
      int unsupportedFiles,
      int failedFiles,
      SyncOutcome outcome,
      List<SourceDiagnostic> diagnostics) {
    public SyncReport {
      Objects.requireNonNull(projectId, "projectId");
      if (indexedFiles < 0 || discoveredFiles < 0 || unsupportedFiles < 0 || failedFiles < 0) {
        throw new IllegalArgumentException("sync counts must not be negative");
      }
      Objects.requireNonNull(outcome, "outcome");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }

  public record SourceDiagnostic(String sourcePath, SourceStatus status, String detail) {
    public SourceDiagnostic {
      Objects.requireNonNull(sourcePath, "sourcePath");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(detail, "detail");
    }
  }

  public enum SyncOutcome {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    SKIPPED
  }

  public enum SourceStatus {
    UNSUPPORTED,
    FAILED
  }
}
