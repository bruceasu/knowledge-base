package me.asu.kb.adapter.document;

import java.util.List;
import java.util.Objects;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;

/** Canonical section object plus its structural position in one document projection. */
public record DocumentSection(
    KnowledgeObject object,
    String parentObjectId,
    int headingLevel,
    List<String> titlePath,
    SourceRange sourceRange) {

  public DocumentSection {
    Objects.requireNonNull(object, "object");
    if (object.type() != ObjectType.SECTION || object.knowledgeClass() != KnowledgeClass.CANONICAL) {
      throw new IllegalArgumentException("Section must contain a canonical SECTION knowledge object");
    }
    parentObjectId = requireText(parentObjectId, "parentObjectId");
    if (headingLevel < 1 || headingLevel > 6) {
      throw new IllegalArgumentException("headingLevel must be between 1 and 6");
    }
    Objects.requireNonNull(titlePath, "titlePath");
    for (String title : titlePath) {
      requireText(title, "titlePath entry");
    }
    titlePath = List.copyOf(titlePath);
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
