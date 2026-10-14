package me.asu.kb.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;

/** Moves deleted source files and their history into workspace-managed trash. */
public final class TrashFileStore {
  private final Path trashRoot;

  public TrashFileStore(Path workspace) {
    this.trashRoot = workspace.toAbsolutePath().normalize().resolve(".kb").resolve("trash");
  }

  public TrashEntry move(ArticleId articleId, Path source, Path historyDirectory, Instant deletedAt)
      throws IOException {
    Objects.requireNonNull(articleId, "articleId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(deletedAt, "deletedAt");
    if (!Files.isRegularFile(source)) {
      throw new IOException("source file is not available: " + source);
    }
    Path entry = trashRoot.resolve(articleId.value() + "-" + deletedAt.toEpochMilli());
    Files.createDirectories(entry);
    Path trashedSource = entry.resolve("source").resolve(source.getFileName());
    moveRecoverably(source, trashedSource);
    if (historyDirectory != null && Files.isDirectory(historyDirectory)) {
      moveRecoverably(historyDirectory, entry.resolve("history"));
    }
    return new TrashEntry(articleId, source.toAbsolutePath().normalize().toString(),
        entry.toString(), deletedAt, deletedAt.plusSeconds(30L * 24 * 60 * 60));
  }

  public Path restore(TrashEntry entry, Path target) throws IOException {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(target, "target");
    if (Files.exists(target)) {
      throw new IOException("restore target already exists: " + target);
    }
    Path source = Path.of(entry.trashPath()).resolve("source");
    Path file;
    try (var files = Files.list(source)) {
      file = files.findFirst()
          .orElseThrow(() -> new IOException("trash source is missing: " + source));
    }
    Files.createDirectories(target.toAbsolutePath().normalize().getParent());
    moveRecoverably(file, target);
    return target;
  }

  public void restoreHistory(TrashEntry entry, ArticleId articleId) throws IOException {
    Path history = Path.of(entry.trashPath()).resolve("history");
    if (!Files.exists(history)) {
      return;
    }
    Path target = historyRoot(articleId);
    if (Files.exists(target)) {
      throw new IOException("history target already exists: " + target);
    }
    moveRecoverably(history, target);
  }

  public void purge(TrashEntry entry) throws IOException {
    deleteRecursively(Path.of(entry.trashPath()));
  }

  private Path historyRoot(ArticleId articleId) {
    return trashRoot.getParent().resolve("history").resolve(Long.toString(articleId.value()));
  }

  private void moveRecoverably(Path source, Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().normalize().getParent());
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      copyRecursively(source, target);
      deleteRecursively(source);
    }
  }

  private void copyRecursively(Path source, Path target) throws IOException {
    if (Files.isDirectory(source)) {
      Files.createDirectories(target);
      try (var files = Files.list(source)) {
        for (Path child : files.toList()) {
          copyRecursively(child, target.resolve(child.getFileName()));
        }
      }
    } else {
      Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }
  }

  private void deleteRecursively(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      try (var files = Files.list(path)) {
        for (Path child : files.toList()) {
          deleteRecursively(child);
        }
      }
    }
    Files.deleteIfExists(path);
  }
}
