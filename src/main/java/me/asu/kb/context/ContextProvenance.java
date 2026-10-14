package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.retrieval.RetrievalSource;

/** Aggregated traceability retained for one selected context item. */
public record ContextProvenance(
    Set<RetrievalSource> contributingSources, List<Evidence> evidence, double confidence) {

  public ContextProvenance {
    contributingSources =
        Set.copyOf(Objects.requireNonNull(contributingSources, "contributingSources"));
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
      throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
    }
  }
}
