package me.asu.kb.service;

import java.time.Instant;
import java.util.Objects;

/** Workspace-managed recoverable deletion record for an Article. */
public record TrashEntry(
    ArticleId articleId,
    String originalPath,
    String trashPath,
    Instant deletedAt,
    Instant expiresAt) {
  public TrashEntry {
    Objects.requireNonNull(articleId, "articleId");
    requireText(originalPath, "originalPath");
    requireText(trashPath, "trashPath");
    Objects.requireNonNull(deletedAt, "deletedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (expiresAt.isBefore(deletedAt)) {
      throw new IllegalArgumentException("Trash expiry cannot precede deletion");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
