package me.asu.kb.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic validation outcome used before any candidate source is invoked. */
record QueryValidation(
    Optional<ValidatedKnowledgeQuery> query, List<RetrievalDiagnostic> diagnostics) {

  QueryValidation {
    query = Objects.requireNonNull(query, "query");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    if (query.isPresent() == !diagnostics.isEmpty()) {
      throw new IllegalArgumentException("validation must contain either a query or diagnostics");
    }
  }

  static QueryValidation valid(ValidatedKnowledgeQuery query) {
    return new QueryValidation(Optional.of(query), List.of());
  }

  static QueryValidation invalid(RetrievalDiagnosticCode code, String detail) {
    return new QueryValidation(
        Optional.empty(),
        List.of(new RetrievalDiagnostic(Optional.empty(), code, detail)));
  }
}
