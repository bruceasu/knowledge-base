package me.asu.kb.retrieval;

import java.util.Objects;

/** Deterministic rank key and human-readable reason for one fused result. */
public record RankExplanation(
    RetrievalFreshness freshness,
    RetrievalMatchCategory matchCategory,
    boolean provenanceComplete,
    int sourceInternalRank,
    String stableFallbackKey,
    String summary) {

  public RankExplanation {
    freshness = Objects.requireNonNull(freshness, "freshness");
    matchCategory = Objects.requireNonNull(matchCategory, "matchCategory");
    if (sourceInternalRank < 0) {
      throw new IllegalArgumentException("sourceInternalRank must not be negative");
    }
    stableFallbackKey = requireText(stableFallbackKey, "stableFallbackKey");
    summary = requireText(summary, "summary");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
