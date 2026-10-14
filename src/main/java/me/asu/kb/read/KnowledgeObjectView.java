package me.asu.kb.read;

import java.util.List;
import java.util.Objects;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.retrieval.RetrievalFreshness;

/** Immutable storage-independent object view with provenance freshness and available evidence. */
public record KnowledgeObjectView(
    KnowledgeObject object, RetrievalFreshness freshness, List<Evidence> evidence) {
  public KnowledgeObjectView {
    object = Objects.requireNonNull(object, "object");
    freshness = Objects.requireNonNull(freshness, "freshness");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
  }
}
