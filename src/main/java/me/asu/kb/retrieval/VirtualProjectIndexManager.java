package me.asu.kb.retrieval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import me.asu.kb.store.SqliteKnowledgeStore;

/** Owns lazily opened, service-local derived indexes for virtual projects. */
public final class VirtualProjectIndexManager implements AutoCloseable {
  public static final int DEFAULT_MAX_OPEN_INDEXES = 8;

  private final Path rootDirectory;
  private final int maxOpenIndexes;
  private final Map<String, Entry> openIndexes = new LinkedHashMap<>(16, 0.75f, true);
  private boolean closed;

  public VirtualProjectIndexManager(Path rootDirectory) throws IOException {
    this(rootDirectory, DEFAULT_MAX_OPEN_INDEXES);
  }

  public VirtualProjectIndexManager(Path rootDirectory, int maxOpenIndexes) throws IOException {
    Objects.requireNonNull(rootDirectory, "rootDirectory");
    if (maxOpenIndexes < 1) {
      throw new IllegalArgumentException("maxOpenIndexes must be positive");
    }
    this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
    this.maxOpenIndexes = maxOpenIndexes;
    Files.createDirectories(this.rootDirectory);
    if (!Files.isDirectory(this.rootDirectory)) {
      throw new IOException("virtual project index root is not a directory");
    }
  }

  /** Returns the stable, service-owned directory assigned to a project. */
  public Path indexDirectory(String projectId) {
    requireProjectId(projectId);
    return rootDirectory.resolve(directoryName(projectId));
  }

  /** Opens or reuses one project index and returns a lease for its use. */
  public synchronized ProjectIndexLease acquire(String projectId) throws IOException {
    requireOpen();
    requireProjectId(projectId);
    Entry entry = openIndexes.get(projectId);
    if (entry == null) {
      evictIdleIndexIfNecessary();
      entry =
          new Entry(
              projectId,
              new EngineeringKnowledgeLuceneProjection(indexDirectory(projectId)));
      openIndexes.put(projectId, entry);
    }
    entry.leases++;
    return new ProjectIndexLease(entry);
  }

  /** Returns the IDs of currently opened indexes in recency order. */
  public synchronized Set<String> openProjectIds() {
    return Set.copyOf(openIndexes.keySet());
  }

  /** Closes an idle project index without deleting its derived directory. */
  public synchronized void closeProject(String projectId) throws IOException {
    requireOpen();
    requireProjectId(projectId);
    Entry entry = openIndexes.get(projectId);
    if (entry == null) {
      return;
    }
    if (entry.leases > 0) {
      throw new IllegalStateException("virtual project index is in use");
    }
    openIndexes.remove(projectId);
    entry.projection.close();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    for (Entry entry : new ArrayList<>(openIndexes.values())) {
      try {
        entry.projection.close();
      } catch (IOException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    openIndexes.clear();
    if (failure != null) {
      throw failure;
    }
  }

  private void evictIdleIndexIfNecessary() throws IOException {
    if (openIndexes.size() < maxOpenIndexes) {
      return;
    }
    String candidateId = null;
    Entry candidate = null;
    for (Map.Entry<String, Entry> current : openIndexes.entrySet()) {
      if (current.getValue().leases == 0) {
        candidateId = current.getKey();
        candidate = current.getValue();
        break;
      }
    }
    if (candidate == null) {
      throw new IllegalStateException("maximum open virtual project indexes reached");
    }
    openIndexes.remove(candidateId);
    candidate.projection.close();
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("virtual project index manager is closed");
    }
  }

  private static void requireProjectId(String projectId) {
    Objects.requireNonNull(projectId, "projectId");
    if (projectId.isBlank()) {
      throw new IllegalArgumentException("projectId must not be blank");
    }
  }

  private static String directoryName(String projectId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "project-" + toHex(digest.digest(projectId.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JRE is missing SHA-256", error);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0xf, 16));
      result.append(Character.forDigit(value & 0xf, 16));
    }
    return result.toString();
  }

  private final class Entry {
    private final String projectId;
    private final EngineeringKnowledgeLuceneProjection projection;
    private int leases;

    private Entry(String projectId, EngineeringKnowledgeLuceneProjection projection) {
      this.projectId = projectId;
      this.projection = projection;
    }
  }

  /** A closeable lease that keeps one project index from being evicted while in use. */
  public final class ProjectIndexLease implements AutoCloseable {
    private final Entry entry;
    private boolean released;

    private ProjectIndexLease(Entry entry) {
      this.entry = entry;
    }

    public String projectId() {
      return entry.projectId;
    }

    public Path directory() {
      return indexDirectory(entry.projectId);
    }

    /** Rebuilds the derived Lucene projection; source files are never copied here. */
    public void rebuild(String workspaceId, List<SqliteKnowledgeStore.StoredObject> objects)
        throws IOException {
      ensureUsable();
      entry.projection.rebuild(workspaceId, objects);
    }

    /** Reads a bounded project-local candidate page without exposing the projection implementation. */
    List<EngineeringKnowledgeLuceneProjection.LexicalHit> search(
        ValidatedKnowledgeQuery query) throws Exception {
      ensureUsable();
      return entry.projection.search(query);
    }

    @Override
    public void close() {
      synchronized (VirtualProjectIndexManager.this) {
        if (released) {
          return;
        }
        released = true;
        entry.leases--;
      }
    }

    private void ensureUsable() {
      synchronized (VirtualProjectIndexManager.this) {
        requireOpen();
        if (released) {
          throw new IllegalStateException("virtual project index lease is closed");
        }
      }
    }
  }
}
