package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;

/** One fused result with stable identity/location, provenance, freshness, and rank explanation. */
public record RetrievalItem(
    Optional<String> objectId,
    ObjectType objectType,
    String title,
    Optional<String> excerpt,
    SourceRef source,
    Optional<KnowledgeClass> knowledgeClass,
    RetrievalFreshness freshness,
    Set<RetrievalSource> contributingSources,
    List<Evidence> evidence,
    RankExplanation rankExplanation,
    Optional<String> virtualProjectId) {

  /** Backward-compatible shared-scope result constructor. */
  public RetrievalItem(
      Optional<String> objectId,
      ObjectType objectType,
      String title,
      Optional<String> excerpt,
      SourceRef source,
      Optional<KnowledgeClass> knowledgeClass,
      RetrievalFreshness freshness,
      Set<RetrievalSource> contributingSources,
      List<Evidence> evidence,
      RankExplanation rankExplanation) {
    this(
        objectId,
        objectType,
        title,
        excerpt,
        source,
        knowledgeClass,
        freshness,
        contributingSources,
        evidence,
        rankExplanation,
        Optional.empty());
  }

  public RetrievalItem {
    objectId = optionalText(objectId, "objectId");
    objectType = Objects.requireNonNull(objectType, "objectType");
    title = requireText(title, "title");
    excerpt = optionalText(excerpt, "excerpt");
    source = Objects.requireNonNull(source, "source");
    knowledgeClass = Objects.requireNonNull(knowledgeClass, "knowledgeClass");
    freshness = Objects.requireNonNull(freshness, "freshness");
    contributingSources = Set.copyOf(Objects.requireNonNull(contributingSources, "contributingSources"));
    if (contributingSources.isEmpty()) {
      throw new IllegalArgumentException("at least one contributing source is required");
    }
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    rankExplanation = Objects.requireNonNull(rankExplanation, "rankExplanation");
    virtualProjectId = optionalText(virtualProjectId, "virtualProjectId");
  }

  private static Optional<String> optionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(item -> requireText(item, name));
    return value;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
