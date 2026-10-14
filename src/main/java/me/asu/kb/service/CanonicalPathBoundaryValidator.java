package me.asu.kb.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Validates document paths against the workspace's registered content boundaries.
 *
 * <p>This component is intentionally lexical and side-effect free. Callers that
 * need symlink or filesystem existence checks must perform those checks at the
 * file-operation boundary.
 */
public final class CanonicalPathBoundaryValidator {

  private final Path workspace;
  private final List<Path> registeredRoots;
  private final Path managedArticles;
  private final Path managedUploads;
  private final Path history;
  private final Path trash;

  public CanonicalPathBoundaryValidator(Path workspace, List<Path> registeredRoots) {
    this.workspace = canonicalAbsolute(workspace, "workspace");
    this.registeredRoots = registeredRoots == null
        ? List.of()
        : registeredRoots.stream().map(root -> canonicalAbsolute(root, "registered root")).toList();
    this.managedArticles = this.workspace.resolve("articles");
    this.managedUploads = this.workspace.resolve("uploads");
    this.history = this.workspace.resolve(".kb").resolve("history");
    this.trash = this.workspace.resolve(".kb").resolve("trash");
  }

  /**
   * Returns the normalized absolute candidate when it is inside an allowed
   * boundary. System history and trash paths are always rejected.
   */
  public Path validate(Path candidate) {
    Objects.requireNonNull(candidate, "candidate");
    if (!candidate.isAbsolute()) {
      throw new IllegalArgumentException("candidate path must be absolute");
    }
    if (containsParentSegment(candidate)) {
      throw new IllegalArgumentException("candidate path must not contain traversal segments");
    }

    Path normalized = candidate.toAbsolutePath().normalize();
    if (isWithin(normalized, history) || isWithin(normalized, trash)) {
      throw new IllegalArgumentException("candidate path is inside a protected system directory");
    }
    if (isWithin(normalized, managedArticles)
        || isWithin(normalized, managedUploads)
        || registeredRoots.stream().anyMatch(root -> isWithin(normalized, root))) {
      return normalized;
    }
    throw new IllegalArgumentException("candidate path is outside an allowed content boundary");
  }

  private static Path canonicalAbsolute(Path path, String label) {
    Objects.requireNonNull(path, label);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(label + " path must be absolute");
    }
    if (containsParentSegment(path)) {
      throw new IllegalArgumentException(label + " path must not contain traversal segments");
    }
    return path.toAbsolutePath().normalize();
  }

  private static boolean isWithin(Path candidate, Path boundary) {
    return candidate.equals(boundary) || candidate.startsWith(boundary);
  }

  private static boolean containsParentSegment(Path path) {
    for (Path segment : path) {
      if ("..".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }
}
