package me.asu.kb.runtime;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.adapter.gitevidence.GitEvidenceSettings;
import me.asu.kb.adapter.gitevidence.GitRepositoryLocator;
import me.asu.kb.service.VirtualProjectPathPolicy;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProject;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceKind;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectResourceReference;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectGitProvenance;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperation;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperationOutcome;
import me.asu.kb.runtime.VirtualProjectApplication.FileOperationResult;

/** SQLite-backed virtual-project metadata application service. */
public final class SqliteVirtualProjectApplication implements VirtualProjectApplication {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1_000;
  private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

  private final SqliteKnowledgeStore store;
  private final VirtualProjectPathPolicy pathPolicy;
  private final GitRepositoryLocator gitRepositoryLocator;
  private final VirtualProjectSynchronizer synchronizer;
  private final VirtualProjectOperationCoordinator operationCoordinator;

  public SqliteVirtualProjectApplication(
      SqliteKnowledgeStore store, VirtualProjectPathPolicy pathPolicy) {
    this(store, pathPolicy, new GitRepositoryLocator(GitEvidenceSettings.unavailable()), null);
  }

  public SqliteVirtualProjectApplication(
      SqliteKnowledgeStore store,
      VirtualProjectPathPolicy pathPolicy,
      GitRepositoryLocator gitRepositoryLocator) {
    this(store, pathPolicy, gitRepositoryLocator, null);
  }

  public SqliteVirtualProjectApplication(
      SqliteKnowledgeStore store,
      VirtualProjectPathPolicy pathPolicy,
      GitRepositoryLocator gitRepositoryLocator,
      VirtualProjectSynchronizer synchronizer) {
    this(
        store,
        pathPolicy,
        gitRepositoryLocator,
        synchronizer,
        synchronizer == null
            ? new VirtualProjectOperationCoordinator()
            : synchronizer.operationCoordinator());
  }

  public SqliteVirtualProjectApplication(
      SqliteKnowledgeStore store,
      VirtualProjectPathPolicy pathPolicy,
      GitRepositoryLocator gitRepositoryLocator,
      VirtualProjectSynchronizer synchronizer,
      VirtualProjectOperationCoordinator operationCoordinator) {
    this.store = Objects.requireNonNull(store, "store");
    this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    this.gitRepositoryLocator = Objects.requireNonNull(gitRepositoryLocator, "gitRepositoryLocator");
    this.synchronizer = synchronizer;
    this.operationCoordinator =
        Objects.requireNonNull(operationCoordinator, "operationCoordinator");
  }

  @Override
  public Object execute(Operation operation, Map<String, Object> arguments) throws SQLException {
    Objects.requireNonNull(operation, "operation");
    Map<String, Object> values = arguments == null ? Map.of() : arguments;
    return switch (operation) {
      case LIST_PROJECTS -> listProjects(values);
      case CREATE_PROJECT -> createProject(values);
      case GET_PROJECT -> getProject(requiredText(values, "projectId"));
      case UPDATE_PROJECT -> updateProject(values);
      case DELETE_PROJECT -> deleteProject(requiredText(values, "projectId"));
      case GET_PROJECT_STATUS -> getProjectStatus(requiredText(values, "projectId"));
      case SYNCHRONIZE_PROJECT -> synchronizeProject(requiredText(values, "projectId"));
      case LIST_RESOURCES -> listResources(values);
      case ADD_RESOURCE -> addResource(values);
      case REMOVE_RESOURCE -> removeResource(values);
      case CREATE_FILE -> fileOperation(FileOperation.CREATE, values);
      case APPEND_FILE -> fileOperation(FileOperation.APPEND, values);
      case REPLACE_FILE -> fileOperation(FileOperation.REPLACE, values);
      case EDIT_FILE -> fileOperation(FileOperation.EDIT, values);
      case DELETE_FILE -> fileOperation(FileOperation.DELETE, values);
    };
  }

  private VirtualProjectSynchronizer.SyncReport synchronizeProject(String projectId)
      throws SQLException {
    if (synchronizer == null) {
      throw new UnsupportedOperationException("virtual project synchronization is unavailable");
    }
    return synchronizer.synchronize(projectId);
  }

  private ProjectStatus getProjectStatus(String projectId) throws SQLException {
    VirtualProject project = findProject(projectId);
    return new ProjectStatus(
        project.id(),
        project.indexStatus(),
        project.syncStatus(),
        project.lastSyncStartedAt(),
        project.lastSyncCompletedAt(),
        project.lastSyncDiagnostic());
  }

  private ProjectList listProjects(Map<String, Object> values) throws SQLException {
    return new ProjectList(store.findVirtualProjects(limit(values)));
  }

  private ProjectView createProject(Map<String, Object> values) throws SQLException {
    String name = requiredText(values, "name");
    String description = optionalText(values, "description");
    VirtualProject project = store.createVirtualProject(name, description);
    return view(project);
  }

  private ProjectView getProject(String projectId) throws SQLException {
    return view(findProject(projectId));
  }

  private ProjectView updateProject(Map<String, Object> values) throws SQLException {
    String projectId = requiredText(values, "projectId");
    String name = requiredText(values, "name");
    String description = optionalText(values, "description");
    findProject(projectId);
    return view(store.updateVirtualProject(projectId, name, description));
  }

  private DeletionResult deleteProject(String projectId) throws SQLException {
    if (store.findVirtualProject(projectId).isEmpty()) {
      throw unknownProject(projectId);
    }
    return new DeletionResult(projectId, null, store.deleteVirtualProject(projectId));
  }

  private ResourceList listResources(Map<String, Object> values) throws SQLException {
    String projectId = requiredText(values, "projectId");
    findProject(projectId);
    return new ResourceList(projectId, store.findVirtualProjectResources(projectId, limit(values)));
  }

  private VirtualProjectResourceReference addResource(Map<String, Object> values)
      throws SQLException {
    String projectId = requiredText(values, "projectId");
    findProject(projectId);
    VirtualProjectResourceKind kind = resourceKind(values.get("kind"));
    Path requested = path(values.get("sourcePath"));
    Path canonical = pathPolicy.requireAllowed(requested);
    validateKind(canonical, kind);
    Optional<VirtualProjectGitProvenance> gitProvenance =
        gitRepositoryLocator
            .locate(canonical)
            .map(
                location ->
                    new VirtualProjectGitProvenance(
                        location.repositoryRoot(), location.relativePath()));
    return store.addVirtualProjectResource(
        projectId, kind, canonical.toString(), gitProvenance);
  }

  private DeletionResult removeResource(Map<String, Object> values) throws SQLException {
    String projectId = requiredText(values, "projectId");
    String referenceId = requiredText(values, "referenceId");
    findProject(projectId);
    boolean deleted = store.deleteVirtualProjectResource(projectId, referenceId);
    if (!deleted) {
      throw new VirtualProjectApplication.NotFoundException("unknown virtual project resource");
    }
    return new DeletionResult(projectId, referenceId, true);
  }

  private FileOperationResult fileOperation(FileOperation operation, Map<String, Object> values)
      throws SQLException {
    String projectId = requiredText(values, "projectId");
    VirtualProjectOperationCoordinator.ProjectLock projectLock =
        operationCoordinator.acquire(projectId);
    try {
      findProject(projectId);
      Path canonical = pathPolicy.requireAllowed(path(values.get("sourcePath")));
      ensureProjectMembership(projectId, canonical);
      return switch (operation) {
        case CREATE -> createFile(projectId, canonical, text(values, "content"));
        case APPEND -> appendFile(projectId, canonical, text(values, "content"));
        case REPLACE -> replaceFile(projectId, canonical, text(values, "content"));
        case EDIT -> editFile(projectId, canonical, text(values, "oldText"), text(values, "newText"));
        case DELETE -> deleteFile(projectId, canonical);
      };
    } finally {
      projectLock.close();
    }
  }

  private FileOperationResult createFile(String projectId, Path canonical, String content) {
    byte[] bytes = utf8Text(content);
    try {
      Path target = revalidateTarget(projectId, canonical);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new VirtualProjectApplication.FileConflictException("file already exists");
      }
      Path parent = target.getParent();
      if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        throw new VirtualProjectApplication.FileOperationException("file parent is unavailable");
      }
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return result(projectId, FileOperation.CREATE, target, bytes.length, true);
    } catch (VirtualProjectApplication.FileConflictException
        | VirtualProjectApplication.FileOperationException failure) {
      throw failure;
    } catch (java.io.IOException failure) {
      throw new VirtualProjectApplication.FileOperationException("file could not be created");
    }
  }

  private FileOperationResult appendFile(String projectId, Path canonical, String content) {
    try {
      Path target = revalidateTarget(projectId, canonical);
      String current = readTextFile(target);
      byte[] bytes = utf8Text(current + content);
      writeExisting(projectId, target, bytes);
      return result(projectId, FileOperation.APPEND, target, bytes.length, true);
    } catch (VirtualProjectApplication.FileOperationException failure) {
      throw failure;
    } catch (java.io.IOException failure) {
      throw new VirtualProjectApplication.FileOperationException("file could not be appended");
    }
  }

  private FileOperationResult replaceFile(String projectId, Path canonical, String content) {
    byte[] bytes = utf8Text(content);
    try {
      writeExisting(projectId, canonical, bytes);
      return result(projectId, FileOperation.REPLACE, canonical, bytes.length, true);
    } catch (VirtualProjectApplication.FileOperationException failure) {
      throw failure;
    } catch (java.io.IOException failure) {
      throw new VirtualProjectApplication.FileOperationException("file could not be replaced");
    }
  }

  private FileOperationResult editFile(
      String projectId, Path canonical, String oldText, String newText) {
    if (oldText.isEmpty()) {
      throw new IllegalArgumentException("oldText must not be empty");
    }
    try {
      Path target = revalidateTarget(projectId, canonical);
      String current = readTextFile(target);
      int first = current.indexOf(oldText);
      if (first < 0 || current.indexOf(oldText, first + oldText.length()) >= 0) {
        throw new VirtualProjectApplication.FileConflictException(
            "oldText must occur exactly once");
      }
      String updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
      byte[] bytes = utf8Text(updated);
      writeExisting(projectId, target, bytes);
      return result(projectId, FileOperation.EDIT, target, bytes.length, true);
    } catch (VirtualProjectApplication.FileConflictException
        | VirtualProjectApplication.FileOperationException failure) {
      throw failure;
    } catch (java.io.IOException failure) {
      throw new VirtualProjectApplication.FileOperationException("file could not be edited");
    }
  }

  private FileOperationResult deleteFile(String projectId, Path canonical) {
    try {
      Path target = revalidateTarget(projectId, canonical);
      requireExistingRegularFile(target);
      Files.delete(target);
      return result(projectId, FileOperation.DELETE, target, 0, true);
    } catch (VirtualProjectApplication.FileOperationException failure) {
      throw failure;
    } catch (java.io.IOException failure) {
      throw new VirtualProjectApplication.FileOperationException("file could not be deleted");
    }
  }

  private void writeExisting(String projectId, Path canonical, byte[] bytes) throws java.io.IOException {
    Path target = revalidateTarget(projectId, canonical);
    requireExistingRegularFile(target);
    Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private String readTextFile(Path canonical) throws java.io.IOException {
    requireExistingRegularFile(canonical);
    return decodeUtf8(Files.readAllBytes(canonical));
  }

  private Path revalidateTarget(String projectId, Path canonical) throws java.io.IOException {
    Path current = pathPolicy.requireAllowed(canonical);
    if (!current.equals(canonical)) {
      throw new VirtualProjectApplication.FileOperationException("file target changed");
    }
    try {
      ensureProjectMembership(projectId, current);
    } catch (SQLException failure) {
      throw new java.io.IOException("project membership could not be verified", failure);
    }
    return current;
  }

  private void ensureProjectMembership(String projectId, Path target) throws SQLException {
    for (VirtualProjectResourceReference reference :
        store.findVirtualProjectResources(projectId, MAX_LIMIT)) {
      Path resource;
      try {
        resource = Path.of(reference.sourcePath()).toAbsolutePath().normalize();
      } catch (RuntimeException invalid) {
        continue;
      }
      if (reference.kind() == VirtualProjectResourceKind.FILE
          ? target.equals(resource)
          : target.equals(resource) || target.startsWith(resource)) {
        return;
      }
    }
    throw new VirtualProjectApplication.FileOperationException(
        "file is not associated with virtual project");
  }

  private static void requireExistingRegularFile(Path target) throws java.io.IOException {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new VirtualProjectApplication.NotFoundException("file does not exist");
    }
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new VirtualProjectApplication.FileOperationException("target is not a regular file");
    }
  }

  private static FileOperationResult result(
      String projectId, FileOperation operation, Path target, long sizeBytes, boolean changed) {
    return new FileOperationResult(
        projectId,
        operation,
        target.toString(),
        FileOperationOutcome.SUCCEEDED,
        changed,
        sizeBytes,
        true);
  }

  private static String text(Map<String, Object> values, String name) {
    if (!values.containsKey(name) || values.get(name) == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return values.get(name).toString();
  }

  private static byte[] utf8Text(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FILE_BYTES || content.indexOf('\0') >= 0) {
      throw new VirtualProjectApplication.FileOperationException(
          "file content exceeds the UTF-8 text limit");
    }
    return bytes;
  }

  private static String decodeUtf8(byte[] bytes) {
    if (bytes.length > MAX_FILE_BYTES) {
      throw new VirtualProjectApplication.FileOperationException(
          "file content exceeds the UTF-8 text limit");
    }
    try {
      String content =
          StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (content.indexOf('\0') >= 0) {
        throw new VirtualProjectApplication.FileOperationException("file is not UTF-8 text");
      }
      return content;
    } catch (CharacterCodingException failure) {
      throw new VirtualProjectApplication.FileOperationException("file is not UTF-8 text");
    }
  }

  private ProjectView view(VirtualProject project) throws SQLException {
    return new ProjectView(project, store.findVirtualProjectResources(project.id(), MAX_LIMIT));
  }

  private VirtualProject findProject(String projectId) throws SQLException {
    return store
        .findVirtualProject(projectId)
        .orElseThrow(() -> unknownProject(projectId));
  }

  private static VirtualProjectApplication.NotFoundException unknownProject(String projectId) {
    return new VirtualProjectApplication.NotFoundException("unknown virtual project: " + projectId);
  }

  private static void validateKind(Path canonical, VirtualProjectResourceKind kind) {
    if (!Files.exists(canonical)) {
      return;
    }
    boolean directory = Files.isDirectory(canonical);
    if (kind == VirtualProjectResourceKind.DIRECTORY && !directory) {
      throw new IllegalArgumentException("resource path is not a directory");
    }
    if (kind == VirtualProjectResourceKind.FILE && directory) {
      throw new IllegalArgumentException("resource path is not a file");
    }
  }

  private static VirtualProjectResourceKind resourceKind(Object value) {
    String text = value == null ? "" : value.toString().trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("kind must be FILE or DIRECTORY");
    }
    try {
      return VirtualProjectResourceKind.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("kind must be FILE or DIRECTORY");
    }
  }

  private static Path path(Object value) {
    String text = value == null ? "" : value.toString().trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("sourcePath must not be blank");
    }
    try {
      return Path.of(text);
    } catch (InvalidPathException invalid) {
      throw new IllegalArgumentException("sourcePath is invalid");
    }
  }

  private static int limit(Map<String, Object> values) {
    Object value = values.get("limit");
    if (value == null) {
      return DEFAULT_LIMIT;
    }
    final int parsed;
    try {
      parsed = Integer.parseInt(value.toString());
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("limit must be a positive integer");
    }
    if (parsed < 1 || parsed > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    return parsed;
  }

  private static String requiredText(Map<String, Object> values, String name) {
    String value = optionalText(values, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String optionalText(Map<String, Object> values, String name) {
    Object value = values.get(name);
    return value == null ? "" : value.toString().trim();
  }
}
