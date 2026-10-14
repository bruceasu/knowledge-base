package me.asu.kb.service;

/** Stable logical Article identity, backed by the existing document identifier. */
public record ArticleId(long value) {
  public ArticleId {
    if (value <= 0) {
      throw new IllegalArgumentException("Article id must be positive");
    }
  }
}
