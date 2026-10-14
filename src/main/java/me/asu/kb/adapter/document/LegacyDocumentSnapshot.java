package me.asu.kb.adapter.document;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.ingest.Chunk;
import me.asu.kb.service.Article;

/**
 * Read-only input collected from the current legacy article model and its already-produced chunks.
 *
 * <p>{@code stableSourceId} is deliberately caller-supplied: the existing SQLite-backed
 * {@code ArticleId} is useful trace data but is not a cross-reingest canonical identity.
 */
public record LegacyDocumentSnapshot(
    String stableSourceId,
    Article article,
    String revisionContent,
    Optional<LegacySourceProvenance> sourceProvenance,
    List<Chunk> chunks,
    Optional<LegacyGeneratorProvenance> legacyChunkGenerator,
    Optional<OpenSpecArtifactKind> explicitOpenSpecArtifactKind,
    Optional<String> repositoryRelativePath) {

  public LegacyDocumentSnapshot {
    stableSourceId = requireText(stableSourceId, "stableSourceId");
    Objects.requireNonNull(article, "article");
    Objects.requireNonNull(revisionContent, "revisionContent");
    Objects.requireNonNull(sourceProvenance, "sourceProvenance");
    chunks = List.copyOf(chunks);
    for (Chunk chunk : chunks) {
      Objects.requireNonNull(chunk, "chunk");
    }
    Objects.requireNonNull(legacyChunkGenerator, "legacyChunkGenerator");
    Objects.requireNonNull(explicitOpenSpecArtifactKind, "explicitOpenSpecArtifactKind");
    repositoryRelativePath = normalize(repositoryRelativePath, "repositoryRelativePath");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Optional<String> normalize(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    return value.filter(item -> !item.isBlank());
  }
}
