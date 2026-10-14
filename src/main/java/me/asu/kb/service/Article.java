package me.asu.kb.service;

import java.util.Objects;

/** Stable logical Article aggregate; metadata belongs to the Article, not a revision. */
public record Article(
    ArticleId id,
    ArticleRevision currentRevision,
    ArticleMetadata metadata,
    ArticleState state,
    ProjectionStatus projection) {
  public Article {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(currentRevision, "currentRevision");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(projection, "projection");
    if (projection.revisionNumber() != currentRevision.revisionNumber()) {
      throw new IllegalArgumentException("Projection must describe the current revision");
    }
  }
}
