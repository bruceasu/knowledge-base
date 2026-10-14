package me.asu.kb.context;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalDiagnosticCode;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.retrieval.RetrievalSource;

/** Resolves one retrieval target and expands only its verified direct relations. */
final class ContextCandidateResolver {
  private static final double MINIMUM_EVIDENCE_CONFIDENCE = 0.5d;
  private static final Set<RelationType> ALLOWED_RELATIONS =
      EnumSet.of(
          RelationType.REFINES,
          RelationType.DESCRIBES,
          RelationType.IMPLEMENTS,
          RelationType.VERIFIED_BY);

  private final ContextBuildCoordinator coordinator;

  ContextCandidateResolver(ContextBuildCoordinator coordinator) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  ContextCandidateResolution resolve(ContextRequest request) {
    ContextBuildCoordinator.Preparation preparation = coordinator.prepare(request);
    List<ContextDiagnostic> diagnostics = new ArrayList<>(preparation.diagnostics());
    List<OmissionRecord> omissions = new ArrayList<>();
    if (!preparation.ready()) {
      return new ContextCandidateResolution(
          preparation.request(),
          Optional.empty(),
          List.of(),
          diagnostics,
          omissions,
          false);
    }

    ContextRequest validatedRequest = preparation.request().orElseThrow();
    RetrievalResult retrieval = preparation.retrieval().orElseThrow();
    recordSourceFailures(retrieval, omissions);
    Optional<RetrievalItem> selected = selectTarget(retrieval.items(), validatedRequest.includeStale());
    recordExcludedFreshness(retrieval.items(), selected, validatedRequest.includeStale(), omissions);
    if (selected.isEmpty()) {
      diagnostics.add(
          diagnostic(ContextDiagnosticCode.TARGET_NOT_FOUND, "no eligible target was found"));
      return new ContextCandidateResolution(
          Optional.of(validatedRequest),
          Optional.empty(),
          List.of(),
          diagnostics,
          omissions,
          false);
    }

    RetrievalItem targetItem = selected.orElseThrow();
    ContextCandidate target = targetCandidate(validatedRequest.workspaceId(), targetItem);
    if (targetItem.objectId().isEmpty()) {
      omissions.add(
          omission(
              OmissionReason.MISSING_OBJECT,
              targetItem.objectId(),
              Optional.of(targetItem.source()),
              "target has no stable object id for relation expansion"));
      diagnostics.add(
          diagnostic(
              ContextDiagnosticCode.RELATION_READ_FAILED,
              "target has no stable object id for relation expansion"));
      return new ContextCandidateResolution(
          Optional.of(validatedRequest),
          Optional.of(target),
          List.of(),
          diagnostics,
          omissions,
          false);
    }

    String targetObjectId = targetItem.objectId().orElseThrow();
    ContextRelationReadResult relationRead =
        coordinator.readDirectRelations(preparation, targetObjectId);
    diagnostics.addAll(relationRead.diagnostics());
    List<ContextCandidate> related =
        filterRelations(validatedRequest, targetObjectId, relationRead.relations(), omissions);
    boolean complete = retrieval.complete() && relationRead.complete() && diagnostics.isEmpty();
    return new ContextCandidateResolution(
        Optional.of(validatedRequest),
        Optional.of(target),
        related,
        diagnostics,
        omissions,
        complete);
  }

  private static Optional<RetrievalItem> selectTarget(
      List<RetrievalItem> items, boolean includeStale) {
    Optional<RetrievalItem> canonical = firstWithFreshness(items, RetrievalFreshness.CURRENT_CANONICAL);
    if (canonical.isPresent()) {
      return canonical;
    }
    Optional<RetrievalItem> derived = firstWithFreshness(items, RetrievalFreshness.CURRENT_DERIVED);
    if (derived.isPresent()) {
      return derived;
    }
    return includeStale ? firstWithFreshness(items, RetrievalFreshness.STALE) : Optional.empty();
  }

  private static void recordSourceFailures(
      RetrievalResult retrieval, List<OmissionRecord> omissions) {
    retrieval.diagnostics().stream()
        .filter(
            diagnostic ->
                diagnostic.code() == RetrievalDiagnosticCode.SOURCE_FAILED
                    || diagnostic.code() == RetrievalDiagnosticCode.SOURCE_UNAVAILABLE)
        .forEach(
            diagnostic ->
                omissions.add(
                    new OmissionRecord(
                        OmissionReason.SOURCE_FAILURE,
                        Optional.empty(),
                        Optional.empty(),
                        diagnostic.detail())));
  }

  private static Optional<RetrievalItem> firstWithFreshness(
      List<RetrievalItem> items, RetrievalFreshness freshness) {
    return items.stream().filter(item -> item.freshness() == freshness).findFirst();
  }

  private static void recordExcludedFreshness(
      List<RetrievalItem> items,
      Optional<RetrievalItem> selected,
      boolean includeStale,
      List<OmissionRecord> omissions) {
    for (RetrievalItem item : items) {
      if (selected.filter(item::equals).isPresent()) {
        continue;
      }
      if (item.freshness() == RetrievalFreshness.STALE
          || item.freshness() == RetrievalFreshness.RETRYABLE
          || item.freshness() == RetrievalFreshness.UNVERIFIED) {
        String detail =
            includeStale
                ? "non-current target candidate was not needed because a better target exists"
                : "non-current target candidate is excluded by default";
        omissions.add(
            omission(
                OmissionReason.STALE,
                item.objectId(),
                Optional.of(item.source()),
                detail));
      }
    }
  }

  private static List<ContextCandidate> filterRelations(
      ContextRequest request,
      String targetObjectId,
      List<DirectContextRelation> relations,
      List<OmissionRecord> omissions) {
    Set<String> currentRelatedObjectIds = new HashSet<>();
    for (DirectContextRelation relation : relations) {
      if (isCurrent(relation.freshness())
          && meetsNonFreshnessPolicy(request, targetObjectId, relation)) {
        currentRelatedObjectIds.add(relation.relatedObject().id());
      }
    }

    List<ContextCandidate> related = new ArrayList<>();
    for (DirectContextRelation relation : relations) {
      Optional<String> objectId = Optional.of(relation.relatedObject().id());
      Optional<me.asu.kb.core.knowledge.SourceRef> source =
          Optional.of(relation.relatedObject().source());
      if (!relation.anchorObjectId().equals(targetObjectId)) {
        omissions.add(
            omission(
                OmissionReason.MISSING_OBJECT,
                objectId,
                source,
                "relation does not use the resolved target as its anchor"));
        continue;
      }
      if (!request.workspaceId().equals(relation.workspaceId())) {
        omissions.add(
            omission(
                OmissionReason.WORKSPACE_MISMATCH,
                objectId,
                source,
                "related object belongs to a different workspace"));
        continue;
      }
      if (!ALLOWED_RELATIONS.contains(relation.type())) {
        omissions.add(
            omission(
                OmissionReason.UNSUPPORTED_RELATION,
                objectId,
                source,
                "relation type is not allowed for context expansion"));
        continue;
      }
      if (relation.evidence().isEmpty()) {
        omissions.add(
            omission(
                OmissionReason.MISSING_EVIDENCE,
                objectId,
                source,
                "relation has no evidence"));
        continue;
      }
      if (relation.evidence().stream()
          .noneMatch(evidence -> evidence.confidence() >= MINIMUM_EVIDENCE_CONFIDENCE)) {
        omissions.add(
            omission(
                OmissionReason.LOW_CONFIDENCE_EVIDENCE,
                objectId,
                source,
                "relation evidence is below the minimum confidence"));
        continue;
      }
      if (!eligibleFreshness(
          relation, request.includeStale(), currentRelatedObjectIds.contains(relation.relatedObject().id()))) {
        omissions.add(
            omission(
                OmissionReason.STALE,
                objectId,
                source,
                "related object is not an eligible current or allowed stale projection"));
        continue;
      }
      related.add(relatedCandidate(request.workspaceId(), relation));
    }
    return List.copyOf(related);
  }

  private static boolean eligibleFreshness(
      DirectContextRelation relation, boolean includeStale, boolean currentAlternativeExists) {
    if (isCurrent(relation.freshness())) {
      return true;
    }
    return relation.freshness() == RetrievalFreshness.STALE
        && includeStale
        && !currentAlternativeExists;
  }

  private static boolean meetsNonFreshnessPolicy(
      ContextRequest request, String targetObjectId, DirectContextRelation relation) {
    return relation.anchorObjectId().equals(targetObjectId)
        && request.workspaceId().equals(relation.workspaceId())
        && ALLOWED_RELATIONS.contains(relation.type())
        && relation.evidence().stream()
            .anyMatch(evidence -> evidence.confidence() >= MINIMUM_EVIDENCE_CONFIDENCE);
  }

  private static boolean isCurrent(RetrievalFreshness freshness) {
    return freshness == RetrievalFreshness.CURRENT_CANONICAL
        || freshness == RetrievalFreshness.CURRENT_DERIVED;
  }

  private static ContextCandidate targetCandidate(String workspaceId, RetrievalItem item) {
    return new ContextCandidate(
        workspaceId,
        item.objectId(),
        item.objectType(),
        item.title(),
        item.excerpt(),
        item.source(),
        item.knowledgeClass(),
        item.freshness(),
        item.contributingSources(),
        item.evidence(),
        true);
  }

  private static ContextCandidate relatedCandidate(
      String workspaceId, DirectContextRelation relation) {
    return new ContextCandidate(
        workspaceId,
        Optional.of(relation.relatedObject().id()),
        relation.relatedObject().type(),
        relation.relatedObject().title(),
        relation.relatedObject().content(),
        relation.relatedObject().source(),
        Optional.of(relation.relatedObject().knowledgeClass()),
        relation.freshness(),
        Set.of(RetrievalSource.STRUCTURED_STORE),
        relation.evidence(),
        false);
  }

  private static ContextDiagnostic diagnostic(ContextDiagnosticCode code, String detail) {
    return new ContextDiagnostic(code, detail, Optional.empty(), Optional.empty());
  }

  private static OmissionRecord omission(
      OmissionReason reason,
      Optional<String> objectId,
      Optional<me.asu.kb.core.knowledge.SourceRef> source,
      String detail) {
    return new OmissionRecord(reason, objectId, source, detail);
  }
}
