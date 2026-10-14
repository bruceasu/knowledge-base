package me.asu.kb.retrieval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves intent-level requests to one registered workspace and fixed internal budgets. */
final class KnowledgeQueryValidator {
  private final Map<String, Path> workspaceRoots;

  KnowledgeQueryValidator(Map<String, Path> workspaceRoots) {
    Objects.requireNonNull(workspaceRoots, "workspaceRoots");
    Map<String, Path> canonical = new LinkedHashMap<>();
    workspaceRoots.forEach(
        (id, root) -> {
          if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid registered workspace id");
          }
          Objects.requireNonNull(root, "workspace root");
          try {
            canonical.put(id, root.toRealPath());
          } catch (IOException failure) {
            throw new IllegalArgumentException("workspace root is unavailable: " + id, failure);
          }
        });
    this.workspaceRoots = Map.copyOf(canonical);
  }

  QueryValidation validate(KnowledgeQuery request) {
    if (request == null) {
      return QueryValidation.invalid(RetrievalDiagnosticCode.INVALID_QUERY, "query is required");
    }
    String workspaceId = request.workspaceId().trim();
    Path workspaceRoot = workspaceRoots.get(workspaceId);
    if (workspaceId.isEmpty() || workspaceRoot == null) {
      return QueryValidation.invalid(
          RetrievalDiagnosticCode.UNKNOWN_WORKSPACE, "workspace is not registered");
    }
    String text = request.text().trim();
    if (text.isEmpty()) {
      return QueryValidation.invalid(RetrievalDiagnosticCode.INVALID_QUERY, "query text is required");
    }
    if (text.length() > RetrievalBudgets.MAX_QUERY_LENGTH) {
      return QueryValidation.invalid(RetrievalDiagnosticCode.INVALID_QUERY, "query text is too long");
    }
    int requestedLimit = request.resultLimit();
    if (requestedLimit < 0 || requestedLimit > RetrievalBudgets.MAX_RESULT_LIMIT) {
      return QueryValidation.invalid(
          RetrievalDiagnosticCode.LIMIT_EXCEEDED, "result limit must be between 0 and 100");
    }
    int resultLimit =
        requestedLimit == 0 ? RetrievalBudgets.DEFAULT_RESULT_LIMIT : requestedLimit;
    RetrievalBudgets budgets = RetrievalBudgets.forResultLimit(resultLimit);
    return QueryValidation.valid(
        new ValidatedKnowledgeQuery(
            workspaceId,
            workspaceRoot,
            text,
            request.objectTypes(),
            request.includeStale(),
            request.requireEvidence(),
            budgets,
            request.virtualProjectId()));
  }
}
