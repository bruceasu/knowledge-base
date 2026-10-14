package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;

/** Validated candidate group sharing a proven stable object identity or precise source location. */
record FusedCandidate(
    RetrievalCandidate preferred,
    RetrievalCandidate rankingCandidate,
    Set<RetrievalSource> contributingSources,
    List<Evidence> evidence,
    boolean provenanceComplete,
    Optional<String> virtualProjectId) {

  FusedCandidate(
      RetrievalCandidate preferred,
      RetrievalCandidate rankingCandidate,
      Set<RetrievalSource> contributingSources,
      List<Evidence> evidence,
      boolean provenanceComplete) {
    this(
        preferred,
        rankingCandidate,
        contributingSources,
        evidence,
        provenanceComplete,
        Optional.empty());
  }

  FusedCandidate {
    preferred = Objects.requireNonNull(preferred, "preferred");
    rankingCandidate = Objects.requireNonNull(rankingCandidate, "rankingCandidate");
    contributingSources = Set.copyOf(Objects.requireNonNull(contributingSources, "contributingSources"));
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    if (contributingSources.isEmpty()) {
      throw new IllegalArgumentException("at least one contributing source is required");
    }
    virtualProjectId = Objects.requireNonNull(virtualProjectId, "virtualProjectId");
  }
}
