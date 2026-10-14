package me.asu.kb.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.asu.kb.core.knowledge.Evidence;

/** Deterministic tiered fusion without cross-source score comparison. */
final class TieredCandidateRanker {
  List<RetrievalItem> rank(List<FusedCandidate> candidates, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    List<Ranked> ranked = new ArrayList<>(candidates.size());
    for (FusedCandidate candidate : candidates) {
      String fallback = stableFallback(candidate);
      RetrievalCandidate representative = candidate.preferred();
      RetrievalCandidate ranking = candidate.rankingCandidate();
      RankExplanation explanation =
          new RankExplanation(
              representative.freshness(),
              ranking.matchCategory(),
              candidate.provenanceComplete(),
              ranking.sourceRank(),
              fallback,
              summary(representative.freshness(), ranking.matchCategory(), candidate));
      RetrievalItem item =
          new RetrievalItem(
              representative.objectId(),
              representative.objectType(),
              representative.title(),
              representative.excerpt(),
              representative.source(),
              representative.knowledgeClass(),
              representative.freshness(),
              candidate.contributingSources(),
              candidate.evidence(),
              explanation,
              candidate.virtualProjectId());
      ranked.add(
          new Ranked(item, ranking.sourceRank(), fallback, candidate.virtualProjectId().isPresent()));
    }
    ranked.sort(
        Comparator.comparing((Ranked value) -> !value.projectCandidate())
            .thenComparingInt(
                (Ranked value) -> freshnessOrder(value.item().rankExplanation().freshness()))
            .thenComparingInt(value -> value.item().rankExplanation().matchCategory().ordinal())
            .thenComparing(value -> !value.item().rankExplanation().provenanceComplete())
            .thenComparingInt(Ranked::sourceRank)
            .thenComparing(Ranked::fallback));
    return ranked.stream().limit(limit).map(Ranked::item).toList();
  }

  private static int freshnessOrder(RetrievalFreshness freshness) {
    return switch (freshness) {
      case CURRENT_CANONICAL -> 0;
      case CURRENT_DERIVED -> 1;
      case STALE -> 2;
      case RETRYABLE -> 3;
      case UNVERIFIED -> 4;
    };
  }

  private static String stableFallback(FusedCandidate candidate) {
    if (candidate.preferred().objectId().isPresent()) {
      return "object:" + candidate.preferred().objectId().orElseThrow();
    }
    if (!candidate.evidence().isEmpty()) {
      Evidence evidence = candidate.evidence().get(0);
      return "location:"
          + evidence.source().system()
          + ":"
          + evidence.source().locator()
          + "#"
          + evidence.location();
    }
    RetrievalCandidate ranking = candidate.rankingCandidate();
    return "candidate:"
        + ranking.stableLocation()
        + ":"
        + ranking.retrievalSource().name()
        + ":"
        + ranking.sourceRank();
  }

  private static String summary(
      RetrievalFreshness freshness,
      RetrievalMatchCategory category,
      FusedCandidate candidate) {
    return "freshness="
        + freshness.name()
        + ", match="
        + category.name()
        + ", provenance="
        + (candidate.provenanceComplete() ? "complete" : "incomplete")
        + ", project="
        + candidate.virtualProjectId().orElse("shared")
        + ", sources="
        + candidate.contributingSources().stream().map(Enum::name).sorted().toList();
  }

  private record Ranked(
      RetrievalItem item, int sourceRank, String fallback, boolean projectCandidate) {}
}
