package me.asu.kb.trace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fixed-range trace result containing no Store, SQL, or Context Builder types. */
public record RelationTraceResult(
    RelationTraceStatus status,
    Optional<RelationTraceObject> startObject,
    List<TracedRelation> relations,
    boolean complete,
    List<RelationTraceDiagnostic> diagnostics) {
  public RelationTraceResult {
    status = Objects.requireNonNull(status, "status");
    startObject = Objects.requireNonNull(startObject, "startObject");
    relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    if ((status == RelationTraceStatus.FOUND) != startObject.isPresent()) {
      throw new IllegalArgumentException("only FOUND results may contain a start object");
    }
    if (status != RelationTraceStatus.FOUND && !relations.isEmpty()) {
      throw new IllegalArgumentException("only FOUND results may contain relations");
    }
  }

  public static RelationTraceResult found(
      RelationTraceObject startObject,
      List<TracedRelation> relations,
      boolean complete,
      List<RelationTraceDiagnostic> diagnostics) {
    return new RelationTraceResult(
        RelationTraceStatus.FOUND,
        Optional.of(startObject),
        relations,
        complete,
        diagnostics);
  }

  public static RelationTraceResult notFound(List<RelationTraceDiagnostic> diagnostics) {
    return new RelationTraceResult(
        RelationTraceStatus.NOT_FOUND, Optional.empty(), List.of(), true, diagnostics);
  }

  public static RelationTraceResult unavailable() {
    return new RelationTraceResult(
        RelationTraceStatus.UNAVAILABLE,
        Optional.empty(),
        List.of(),
        false,
        List.of(
            new RelationTraceDiagnostic(
                RelationTraceDiagnostic.Code.STORE_UNAVAILABLE,
                "relation source is unavailable")));
  }
}
