package me.asu.kb.adapter.document;

import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;

/** Existing fixed-size chunk represented only as a derived knowledge projection. */
public record DerivedChunkProjection(
    KnowledgeObject object,
    Optional<String> sectionId,
    Optional<SourceRange> sourceRange,
    int legacyChunkNumber) {

  public DerivedChunkProjection {
    Objects.requireNonNull(object, "object");
    if (object.type() != ObjectType.CHUNK || object.knowledgeClass() != KnowledgeClass.DERIVED) {
      throw new IllegalArgumentException("Chunk must contain a derived CHUNK knowledge object");
    }
    Objects.requireNonNull(sectionId, "sectionId");
    sectionId.ifPresent(value -> requireText(value, "sectionId"));
    Objects.requireNonNull(sourceRange, "sourceRange");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
