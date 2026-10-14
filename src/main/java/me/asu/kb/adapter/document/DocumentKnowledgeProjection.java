package me.asu.kb.adapter.document;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;

/** Complete in-memory result of one legacy document adaptation attempt. */
public record DocumentKnowledgeProjection(
    Optional<KnowledgeObject> document,
    Optional<SourceRange> documentSourceRange,
    List<DocumentSection> sections,
    List<DerivedChunkProjection> derivedChunks,
    List<AdapterDiagnostic> diagnostics) {

  public DocumentKnowledgeProjection {
    Objects.requireNonNull(document, "document");
    document.ifPresent(
        object -> {
          if (object.knowledgeClass() != KnowledgeClass.CANONICAL) {
            throw new IllegalArgumentException("Document must be canonical");
          }
        });
    Objects.requireNonNull(documentSourceRange, "documentSourceRange");
    sections = List.copyOf(sections);
    derivedChunks = List.copyOf(derivedChunks);
    diagnostics = List.copyOf(diagnostics);
    if (document.isEmpty() && (!sections.isEmpty() || !derivedChunks.isEmpty())) {
      throw new IllegalArgumentException("Projection without a document cannot contain child objects");
    }
  }
}
