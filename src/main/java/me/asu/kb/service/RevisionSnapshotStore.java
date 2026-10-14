package me.asu.kb.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stores immutable UTF-8 snapshots outside normal content roots. */
public final class RevisionSnapshotStore {

  private final Path historyRoot;

  public RevisionSnapshotStore(Path workspace) {
    Objects.requireNonNull(workspace, "workspace");
    this.historyRoot = workspace.toAbsolutePath().normalize().resolve(".kb").resolve("history");
  }

  public Path write(ArticleId articleId, ArticleRevision revision, String content) throws IOException {
    Objects.requireNonNull(articleId, "articleId");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(content, "content");
    Path articleDirectory = historyRoot.resolve(Long.toString(articleId.value()));
    Files.createDirectories(articleDirectory);
    Path target = articleDirectory.resolve(revision.revisionNumber() + extension(revision.sourcePath()));
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel =
        FileChannel.open(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(bytes));
      channel.force(true);
    }
    return target;
  }

  public Path writeIfAbsent(ArticleId articleId, ArticleRevision revision, String content)
      throws IOException {
    Path target = historyRoot.resolve(Long.toString(articleId.value()))
        .resolve(revision.revisionNumber() + extension(revision.sourcePath()));
    if (Files.exists(target)) {
      return target;
    }
    return write(articleId, revision, content);
  }

  public String read(ArticleId articleId, long revisionNumber) throws IOException {
    Objects.requireNonNull(articleId, "articleId");
    if (revisionNumber <= 0) {
      throw new IllegalArgumentException("revisionNumber must be positive");
    }
    Path directory = historyRoot.resolve(Long.toString(articleId.value()));
    try (var files = Files.list(directory)) {
      Path snapshot = files
          .filter(path -> path.getFileName().toString().startsWith(revisionNumber + "."))
          .findFirst()
          .orElseThrow(() -> new IOException("revision snapshot not found: " + revisionNumber));
      return Files.readString(snapshot, StandardCharsets.UTF_8);
    }
  }

  public List<Path> list(ArticleId articleId) throws IOException {
    Objects.requireNonNull(articleId, "articleId");
    Path directory = historyRoot.resolve(Long.toString(articleId.value()));
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var files = Files.list(directory)) {
      return files.sorted().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
  }

  public void delete(ArticleId articleId, ArticleRevision revision) throws IOException {
    Path target = historyRoot.resolve(Long.toString(articleId.value()))
        .resolve(revision.revisionNumber() + extension(revision.sourcePath()));
    Files.deleteIfExists(target);
  }

  private static String extension(String sourcePath) {
    String name = Path.of(sourcePath).getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot) : ".txt";
  }
}
