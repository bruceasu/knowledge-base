package me.asu.kb.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Direct relations and explicit diagnostics returned by the controlled read seam. */
record ContextRelationReadResult(
    List<DirectContextRelation> relations,
    List<ContextDiagnostic> diagnostics,
    boolean complete) {

  ContextRelationReadResult {
    relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    if (complete && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("a complete relation read cannot contain diagnostics");
    }
  }

  static ContextRelationReadResult failed(String objectId, String detail) {
    return new ContextRelationReadResult(
        List.of(),
        List.of(
            new ContextDiagnostic(
                ContextDiagnosticCode.RELATION_READ_FAILED,
                detail,
                Optional.of(objectId),
                Optional.empty())),
        false);
  }
}
