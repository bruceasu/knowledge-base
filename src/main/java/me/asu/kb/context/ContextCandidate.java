package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalSource;

/** Traceable target or direct-relation candidate before deduplication and budget packing. */
record ContextCandidate(
    String workspaceId,
    Optional<String> objectId,
    ObjectType objectType,
    String title,
    Optional<String> content,
    SourceRef source,
    Optional<KnowledgeClass> knowledgeClass,
    RetrievalFreshness freshness,
    Set<RetrievalSource> contributingSources,
    List<Evidence> evidence,
    boolean target) {

  ContextCandidate {
    workspaceId = requireText(workspaceId, "workspaceId");
    objectId = optionalText(objectId, "objectId");
    objectType = Objects.requireNonNull(objectType, "objectType");
    title = requireText(title, "title");
    content = optionalText(content, "content");
    source = Objects.requireNonNull(source, "source");
    knowledgeClass = Objects.requireNonNull(knowledgeClass, "knowledgeClass");
    freshness = Objects.requireNonNull(freshness, "freshness");
    contributingSources =
        Set.copyOf(Objects.requireNonNull(contributingSources, "contributingSources"));
    if (contributingSources.isEmpty()) {
      throw new IllegalArgumentException("at least one contributing source is required");
    }
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
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
