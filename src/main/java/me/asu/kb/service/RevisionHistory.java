package me.asu.kb.service;

import java.util.List;
import java.util.Objects;

/** Ordered immutable revision snapshots for one Article, including its current revision. */
public record RevisionHistory(ArticleId articleId, List<ArticleRevision> revisions) {
  public RevisionHistory {
    Objects.requireNonNull(articleId, "articleId");
    Objects.requireNonNull(revisions, "revisions");
    if (revisions.isEmpty()) {
      throw new IllegalArgumentException("Revision history must contain at least one revision");
    }
    revisions = List.copyOf(revisions);
    for (int i = 0; i < revisions.size(); i++) {
      if (revisions.get(i).revisionNumber() != i + 1L) {
        throw new IllegalArgumentException("Revision numbers must be contiguous and ordered");
      }
    }
  }

  public ArticleRevision current() {
    return revisions.get(revisions.size() - 1);
  }
}
