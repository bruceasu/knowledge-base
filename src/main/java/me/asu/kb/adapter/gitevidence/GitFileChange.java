package me.asu.kb.adapter.gitevidence;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;

/** One immutable path-level change fact; no file content or semantic relation is implied. */
public record GitFileChange(
    GitChangeKind kind,
    Optional<String> oldPath,
    Optional<String> newPath,
    boolean binary,
    Evidence evidence) {

  public GitFileChange {
    kind = Objects.requireNonNull(kind, "kind");
    oldPath = optionalPath(oldPath, "oldPath");
    newPath = optionalPath(newPath, "newPath");
    evidence = Objects.requireNonNull(evidence, "evidence");
    if (evidence.type() != EvidenceType.VERSION_CONTROL) {
      throw new IllegalArgumentException("evidence must be version control");
    }
    validateShape(kind, oldPath, newPath);
  }

  private static Optional<String> optionalPath(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(path -> requireText(path, name));
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.startsWith("/")
        || value.endsWith("/")
        || value.indexOf('\\') >= 0
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must be a normalized relative path");
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(name + " must be a normalized relative path");
      }
    }
    return value;
  }

  private static void validateShape(
      GitChangeKind kind, Optional<String> oldPath, Optional<String> newPath) {
    boolean valid =
        switch (kind) {
          case ADD -> oldPath.isEmpty() && newPath.isPresent();
          case DELETE -> oldPath.isPresent() && newPath.isEmpty();
          case MODIFY -> oldPath.isPresent() && oldPath.equals(newPath);
          case RENAME, COPY ->
              oldPath.isPresent() && newPath.isPresent() && !oldPath.equals(newPath);
        };
    if (!valid) {
      throw new IllegalArgumentException("paths do not match the Git change kind");
    }
  }
}
