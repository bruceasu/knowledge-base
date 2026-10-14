package me.asu.kb.read;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Controlled object-read result with no Store or SQL types. */
public record KnowledgeObjectReadResult(
    KnowledgeObjectReadStatus status,
    Optional<KnowledgeObjectView> object,
    List<KnowledgeObjectReadDiagnostic> diagnostics) {
  public KnowledgeObjectReadResult {
    status = Objects.requireNonNull(status, "status");
    object = Objects.requireNonNull(object, "object");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    if ((status == KnowledgeObjectReadStatus.FOUND) != object.isPresent()) {
      throw new IllegalArgumentException("only FOUND results may contain an object");
    }
  }

  public static KnowledgeObjectReadResult found(
      KnowledgeObjectView object, List<KnowledgeObjectReadDiagnostic> diagnostics) {
    return new KnowledgeObjectReadResult(
        KnowledgeObjectReadStatus.FOUND, Optional.of(object), diagnostics);
  }

  public static KnowledgeObjectReadResult notFound(
      List<KnowledgeObjectReadDiagnostic> diagnostics) {
    return new KnowledgeObjectReadResult(
        KnowledgeObjectReadStatus.NOT_FOUND, Optional.empty(), diagnostics);
  }

  public static KnowledgeObjectReadResult unavailable() {
    return new KnowledgeObjectReadResult(
        KnowledgeObjectReadStatus.UNAVAILABLE,
        Optional.empty(),
        List.of(
            new KnowledgeObjectReadDiagnostic(
                KnowledgeObjectReadDiagnostic.Code.STORE_UNAVAILABLE,
                "knowledge object source is unavailable")));
  }
}
