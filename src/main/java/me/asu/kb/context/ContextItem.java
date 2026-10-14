package me.asu.kb.context;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;

/** One ordered, source-located and budget-accounted context fragment. */
public record ContextItem(
    Optional<String> objectId,
    ObjectType objectType,
    String title,
    String fragment,
    SourceRef source,
    Optional<ContextSourceRange> sourceRange,
    Optional<KnowledgeClass> knowledgeClass,
    RetrievalFreshness freshness,
    ContextProvenance provenance,
    ContextSelectionReason selectionReason,
    boolean truncated,
    int estimatedTokens) {

  public ContextItem {
    objectId = optionalText(objectId, "objectId");
    objectType = Objects.requireNonNull(objectType, "objectType");
    title = requireText(title, "title");
    fragment = Objects.requireNonNull(fragment, "fragment");
    source = Objects.requireNonNull(source, "source");
    sourceRange = Objects.requireNonNull(sourceRange, "sourceRange");
    knowledgeClass = Objects.requireNonNull(knowledgeClass, "knowledgeClass");
    freshness = Objects.requireNonNull(freshness, "freshness");
    provenance = Objects.requireNonNull(provenance, "provenance");
    selectionReason = Objects.requireNonNull(selectionReason, "selectionReason");
    int expectedEstimate =
        ContextTokenEstimator.estimate(objectType, objectId, source, sourceRange, fragment);
    if (estimatedTokens != expectedEstimate) {
      throw new IllegalArgumentException(
          "estimatedTokens must match " + ContextTokenEstimator.VERSION);
    }
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
