package me.asu.kb.core.knowledge;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage-independent engineering knowledge with explicit canonical or derived provenance.
 *
 * <p>The identifier is a caller-supplied stable identifier. This core deliberately does not map it
 * to the current SQLite article or chunk identifiers; that policy belongs to a later adapter.
 */
public record KnowledgeObject(
    String id,
    ObjectType type,
    String title,
    Optional<String> content,
    SourceRef source,
    Map<String, String> metadata,
    KnowledgeClass knowledgeClass,
    String contentHash,
    Optional<String> canonicalSourceId,
    Optional<String> generatorId,
    Optional<String> generatorVersion,
    Optional<String> inputContentHash) {

  public KnowledgeObject {
    id = requireText(id, "id");
    Objects.requireNonNull(type, "type");
    title = requireText(title, "title");
    content = requireOptionalText(content, "content");
    Objects.requireNonNull(source, "source");
    metadata = copyMetadata(metadata);
    Objects.requireNonNull(knowledgeClass, "knowledgeClass");
    contentHash = requireText(contentHash, "contentHash");
    canonicalSourceId = requireOptionalText(canonicalSourceId, "canonicalSourceId");
    generatorId = requireOptionalText(generatorId, "generatorId");
    generatorVersion = requireOptionalText(generatorVersion, "generatorVersion");
    inputContentHash = requireOptionalText(inputContentHash, "inputContentHash");

    if (knowledgeClass == KnowledgeClass.CANONICAL) {
      requireNoDerivedProvenance(
          canonicalSourceId, generatorId, generatorVersion, inputContentHash);
    } else {
      requireDerivedProvenance(
          canonicalSourceId, generatorId, generatorVersion, inputContentHash);
    }
  }

  public static KnowledgeObject canonical(
      String id,
      ObjectType type,
      String title,
      Optional<String> content,
      SourceRef source,
      Map<String, String> metadata,
      String contentHash) {
    return new KnowledgeObject(
        id,
        type,
        title,
        content,
        source,
        metadata,
        KnowledgeClass.CANONICAL,
        contentHash,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public static KnowledgeObject derived(
      String id,
      ObjectType type,
      String title,
      Optional<String> content,
      SourceRef source,
      Map<String, String> metadata,
      String contentHash,
      String canonicalSourceId,
      String generatorId,
      String generatorVersion,
      String inputContentHash) {
    return new KnowledgeObject(
        id,
        type,
        title,
        content,
        source,
        metadata,
        KnowledgeClass.DERIVED,
        contentHash,
        Optional.of(canonicalSourceId),
        Optional.of(generatorId),
        Optional.of(generatorVersion),
        Optional.of(inputContentHash));
  }

  private static Map<String, String> copyMetadata(Map<String, String> metadata) {
    Objects.requireNonNull(metadata, "metadata");
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      requireText(entry.getKey(), "metadata key");
      Objects.requireNonNull(entry.getValue(), "metadata value");
    }
    return Map.copyOf(metadata);
  }

  private static Optional<String> requireOptionalText(Optional<String> value, String name) {
    Objects.requireNonNull(value, name);
    value.ifPresent(item -> requireText(item, name));
    return value;
  }

  private static void requireNoDerivedProvenance(
      Optional<String> canonicalSourceId,
      Optional<String> generatorId,
      Optional<String> generatorVersion,
      Optional<String> inputContentHash) {
    if (canonicalSourceId.isPresent()
        || generatorId.isPresent()
        || generatorVersion.isPresent()
        || inputContentHash.isPresent()) {
      throw new IllegalArgumentException("Canonical knowledge must not declare derived provenance");
    }
  }

  private static void requireDerivedProvenance(
      Optional<String> canonicalSourceId,
      Optional<String> generatorId,
      Optional<String> generatorVersion,
      Optional<String> inputContentHash) {
    if (canonicalSourceId.isEmpty()
        || generatorId.isEmpty()
        || generatorVersion.isEmpty()
        || inputContentHash.isEmpty()) {
      throw new IllegalArgumentException(
          "Derived knowledge requires canonical source, generator identity and version, and input content hash");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
