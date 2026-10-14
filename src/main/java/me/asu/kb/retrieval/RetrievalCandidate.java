package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;

/** Backend-neutral candidate; source scores and adapter result types stop at normalization. */
record RetrievalCandidate(
    Optional<String> objectId,
    ObjectType objectType,
    String title,
    Optional<String> excerpt,
    SourceRef source,
    Optional<KnowledgeClass> knowledgeClass,
    RetrievalFreshness freshness,
    RetrievalMatchCategory matchCategory,
    RetrievalSource retrievalSource,
    int sourceRank,
    boolean provenanceComplete,
    List<Evidence> evidence,
    Optional<String> inputContentHash,
    Optional<String> projectionVersion,
    Optional<String> virtualProjectId) {

  /** Backward-compatible shared-scope candidate constructor. */
  RetrievalCandidate(
      Optional<String> objectId,
      ObjectType objectType,
      String title,
      Optional<String> excerpt,
      SourceRef source,
      Optional<KnowledgeClass> knowledgeClass,
      RetrievalFreshness freshness,
      RetrievalMatchCategory matchCategory,
      RetrievalSource retrievalSource,
      int sourceRank,
      boolean provenanceComplete,
      List<Evidence> evidence,
      Optional<String> inputContentHash,
      Optional<String> projectionVersion) {
    this(
        objectId,
        objectType,
        title,
        excerpt,
        source,
        knowledgeClass,
        freshness,
        matchCategory,
        retrievalSource,
        sourceRank,
        provenanceComplete,
        evidence,
        inputContentHash,
        projectionVersion,
        Optional.empty());
  }

  RetrievalCandidate {
    objectId = optionalText(objectId, "objectId");
    objectType = Objects.requireNonNull(objectType, "objectType");
    title = requireText(title, "title");
    excerpt = optionalText(excerpt, "excerpt");
    source = Objects.requireNonNull(source, "source");
    knowledgeClass = Objects.requireNonNull(knowledgeClass, "knowledgeClass");
    freshness = Objects.requireNonNull(freshness, "freshness");
    matchCategory = Objects.requireNonNull(matchCategory, "matchCategory");
    retrievalSource = Objects.requireNonNull(retrievalSource, "retrievalSource");
    if (sourceRank < 0) {
      throw new IllegalArgumentException("sourceRank must not be negative");
    }
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    inputContentHash = optionalText(inputContentHash, "inputContentHash");
    projectionVersion = optionalText(projectionVersion, "projectionVersion");
    virtualProjectId = optionalText(virtualProjectId, "virtualProjectId");
  }

  boolean belongsToVirtualProject() {
    return virtualProjectId.isPresent();
  }

  String stableLocation() {
    return source.system() + ":" + source.locator() + source.revision().map(value -> "@" + value).orElse("");
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
