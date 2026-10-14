package me.asu.kb.service;

import java.time.Instant;
import java.util.Objects;

/** Immutable text snapshot associated with an Article. */
public record ArticleRevision(
    long revisionNumber,
    String contentHash,
    String format,
    String sourcePath,
    ArticleSourceKind sourceKind,
    Instant createdAt) {

  public ArticleRevision {
    if (revisionNumber <= 0) {
      throw new IllegalArgumentException("Revision number must be positive");
    }
    requireText(contentHash, "contentHash");
    requireText(format, "format");
    requireText(sourcePath, "sourcePath");
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
