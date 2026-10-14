package me.asu.kb.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Packs prioritized candidates into the request budget without generated content. */
final class ContextBudgetPacker {

  EngineeringContext pack(
      ContextRequest originalRequest, ContextCandidateOrganization organization) {
    ContextRequest request = organization.request().orElse(originalRequest);
    String workspaceId = request == null ? "" : request.workspaceId();
    String target = request == null ? "" : request.target();
    Optional<ContextIntent> intent =
        request == null ? Optional.empty() : Optional.ofNullable(request.intent());
    if (request == null || organization.request().isEmpty()) {
      return new EngineeringContext(
          workspaceId,
          target,
          intent,
          List.of(),
          0,
          ContextTokenEstimator.VERSION,
          false,
          organization.diagnostics(),
          organization.omissions());
    }

    List<ContextItem> items = new ArrayList<>();
    List<OmissionRecord> omissions = new ArrayList<>(organization.omissions());
    int remaining = request.tokenBudget();
    boolean budgetLimited = false;
    List<SelectedContextCandidate> candidates = organization.candidates();

    if (!candidates.isEmpty()
        && candidates.get(0).selectionReason() == ContextSelectionReason.TARGET
        && headerEstimate(candidates.get(0)) > remaining) {
      for (SelectedContextCandidate candidate : candidates) {
        omissions.add(budgetOmission(candidate, "target source header does not fit the budget"));
      }
      return context(request, items, organization, omissions, false);
    }

    for (SelectedContextCandidate selected : candidates) {
      ContextCandidate candidate = selected.candidate();
      String fragment = ContextTokenEstimator.normalizeLineEndings(candidate.content().orElse(""));
      int fullEstimate = estimate(selected, fragment);
      if (fullEstimate <= remaining) {
        ContextItem item = item(selected, fragment, false, fullEstimate);
        items.add(item);
        remaining -= fullEstimate;
        continue;
      }

      int headerEstimate = headerEstimate(selected);
      if (headerEstimate <= remaining) {
        String truncated =
            ContextTokenEstimator.truncateToUtf8Bytes(fragment, remaining - headerEstimate);
        int truncatedEstimate = estimate(selected, truncated);
        items.add(item(selected, truncated, true, truncatedEstimate));
        remaining -= truncatedEstimate;
        omissions.add(budgetOmission(selected, "content remainder omitted after deterministic truncation"));
      } else {
        omissions.add(budgetOmission(selected, "source header does not fit the remaining budget"));
      }
      budgetLimited = true;
    }

    boolean complete = organization.complete() && !budgetLimited;
    return context(request, items, organization, omissions, complete);
  }

  private static EngineeringContext context(
      ContextRequest request,
      List<ContextItem> items,
      ContextCandidateOrganization organization,
      List<OmissionRecord> omissions,
      boolean complete) {
    return new EngineeringContext(
        request.workspaceId(),
        request.target(),
        Optional.of(request.intent()),
        items,
        ContextTokenEstimator.total(items),
        ContextTokenEstimator.VERSION,
        complete,
        organization.diagnostics(),
        omissions);
  }

  private static ContextItem item(
      SelectedContextCandidate selected,
      String fragment,
      boolean truncated,
      int estimatedTokens) {
    ContextCandidate candidate = selected.candidate();
    return new ContextItem(
        candidate.objectId(),
        candidate.objectType(),
        candidate.title(),
        fragment,
        candidate.source(),
        Optional.empty(),
        candidate.knowledgeClass(),
        candidate.freshness(),
        selected.provenance(),
        selected.selectionReason(),
        truncated,
        estimatedTokens);
  }

  private static int estimate(SelectedContextCandidate selected, String fragment) {
    ContextCandidate candidate = selected.candidate();
    return ContextTokenEstimator.estimate(
        candidate.objectType(),
        candidate.objectId(),
        candidate.source(),
        Optional.empty(),
        fragment);
  }

  private static int headerEstimate(SelectedContextCandidate selected) {
    ContextCandidate candidate = selected.candidate();
    return ContextTokenEstimator.estimateHeader(
        candidate.objectType(), candidate.objectId(), candidate.source(), Optional.empty());
  }

  private static OmissionRecord budgetOmission(
      SelectedContextCandidate selected, String detail) {
    ContextCandidate candidate = selected.candidate();
    return new OmissionRecord(
        OmissionReason.BUDGET,
        candidate.objectId(),
        Optional.of(candidate.source()),
        detail);
  }
}
