package me.asu.kb.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.retrieval.KnowledgeQuery;
import me.asu.kb.retrieval.KnowledgeRetriever;
import me.asu.kb.retrieval.RetrievalDiagnostic;
import me.asu.kb.retrieval.RetrievalResult;

/** Validates a context request and coordinates its application-layer read dependencies. */
final class ContextBuildCoordinator {
  static final int MINIMUM_TOKEN_BUDGET = ContextTokenEstimator.MINIMUM_BUDGET;

  private final KnowledgeRetriever retriever;
  private final ContextRelationReader relationReader;

  ContextBuildCoordinator(KnowledgeRetriever retriever, ContextRelationReader relationReader) {
    this.retriever = Objects.requireNonNull(retriever, "retriever");
    this.relationReader = Objects.requireNonNull(relationReader, "relationReader");
  }

  Preparation prepare(ContextRequest request) {
    List<ContextDiagnostic> validationDiagnostics = validate(request);
    if (!validationDiagnostics.isEmpty()) {
      return new Preparation(Optional.empty(), Optional.empty(), validationDiagnostics);
    }

    ContextRequest normalized = normalize(request);
    RetrievalResult retrieval;
    try {
      retrieval =
          retriever.retrieve(
              new KnowledgeQuery(
                  normalized.workspaceId(),
                  normalized.target(),
                  Set.of(),
                  0,
                  normalized.includeStale(),
                  normalized.requireEvidence()));
    } catch (RuntimeException failure) {
      return failedPreparation(
          normalized, ContextDiagnosticCode.SOURCE_UNAVAILABLE, "knowledge retrieval failed");
    }

    if (retrieval == null) {
      return failedPreparation(
          normalized,
          ContextDiagnosticCode.SOURCE_UNAVAILABLE,
          "knowledge retrieval returned no result");
    }
    if (!normalized.workspaceId().equals(retrieval.workspaceId())) {
      return failedPreparation(
          normalized,
          ContextDiagnosticCode.RETRIEVAL_INCOMPLETE,
          "retrieval result workspace does not match the request");
    }

    List<ContextDiagnostic> diagnostics = new ArrayList<>(retrieval.diagnostics().size());
    for (RetrievalDiagnostic diagnostic : retrieval.diagnostics()) {
      diagnostics.add(
          new ContextDiagnostic(
              ContextDiagnosticCode.RETRIEVAL_INCOMPLETE,
              diagnostic.detail(),
              Optional.empty(),
              Optional.of(diagnostic)));
    }
    return new Preparation(Optional.of(normalized), Optional.of(retrieval), diagnostics);
  }

  ContextRelationReadResult readDirectRelations(Preparation preparation, String objectId) {
    Objects.requireNonNull(preparation, "preparation");
    if (!preparation.ready()) {
      throw new IllegalStateException("a successful preparation is required");
    }
    if (objectId == null || objectId.isBlank()) {
      throw new IllegalArgumentException("objectId must not be blank");
    }

    ContextRequest request = preparation.request().orElseThrow();
    try {
      ContextRelationReadResult result =
          relationReader.readDirectRelations(request.workspaceId(), objectId);
      if (result != null) {
        return result;
      }
    } catch (RuntimeException failure) {
      // The application boundary reports a stable diagnostic instead of leaking infrastructure errors.
    }
    return ContextRelationReadResult.failed(objectId, "relation read failed");
  }

  private static List<ContextDiagnostic> validate(ContextRequest request) {
    if (request == null) {
      return List.of(invalid("request must not be null"));
    }
    List<ContextDiagnostic> diagnostics = new ArrayList<>();
    if (request.workspaceId().isBlank()) {
      diagnostics.add(invalid("workspaceId must not be blank"));
    }
    if (request.target().isBlank()) {
      diagnostics.add(invalid("target must not be blank"));
    }
    if (request.intent() == null) {
      diagnostics.add(invalid("intent must be supported"));
    }
    if (request.tokenBudget() < MINIMUM_TOKEN_BUDGET) {
      diagnostics.add(
          invalid("tokenBudget must be at least " + MINIMUM_TOKEN_BUDGET));
    }
    return List.copyOf(diagnostics);
  }

  private static ContextRequest normalize(ContextRequest request) {
    return new ContextRequest(
        request.workspaceId().trim(),
        request.target().trim(),
        request.intent(),
        request.tokenBudget(),
        request.includeStale(),
        request.requireEvidence());
  }

  private static Preparation failedPreparation(
      ContextRequest request, ContextDiagnosticCode code, String detail) {
    return new Preparation(
        Optional.of(request),
        Optional.empty(),
        List.of(new ContextDiagnostic(code, detail, Optional.empty(), Optional.empty())));
  }

  private static ContextDiagnostic invalid(String detail) {
    return new ContextDiagnostic(
        ContextDiagnosticCode.INVALID_REQUEST, detail, Optional.empty(), Optional.empty());
  }

  record Preparation(
      Optional<ContextRequest> request,
      Optional<RetrievalResult> retrieval,
      List<ContextDiagnostic> diagnostics) {
    Preparation {
      request = Objects.requireNonNull(request, "request");
      retrieval = Objects.requireNonNull(retrieval, "retrieval");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if (retrieval.isPresent() && request.isEmpty()) {
        throw new IllegalArgumentException("retrieval requires a validated request");
      }
    }

    boolean ready() {
      return request.isPresent() && retrieval.isPresent();
    }
  }
}
