package me.asu.kb.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Article-level metadata inherited by every content revision. */
public record ArticleMetadata(
    Optional<String> title,
    Optional<String> summary,
    List<String> keywords,
    List<String> tags) {

  public ArticleMetadata {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(keywords, "keywords");
    Objects.requireNonNull(tags, "tags");
    keywords = List.copyOf(keywords);
    tags = List.copyOf(tags);
  }

  public static ArticleMetadata empty() {
    return new ArticleMetadata(Optional.empty(), Optional.empty(), List.of(), List.of());
  }
}
