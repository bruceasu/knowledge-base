package me.asu.kb.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RankExplanation;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalMatchCategory;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.retrieval.RetrievalSource;
import org.junit.jupiter.api.Test;

class DefaultContextBuilderTargetResolutionTest {
  private static final String WORKSPACE_ID = "fixture";

  @Test
  void locatesCurrentCanonicalTargetAndExpandsSupportedDirectRelation() {
    RetrievalItem target =
        retrievalItem(
            "symbol:builder",
            ObjectType.CODE_SYMBOL,
            RetrievalFreshness.CURRENT_CANONICAL,
            Set.of(RetrievalSource.STRUCTURED_STORE),
            List.of());
    KnowledgeObject design =
        canonicalObject("design:context", ObjectType.DESIGN, "Deterministic context design");
    Evidence relationEvidence = evidence(EvidenceType.SOURCE_TEXT, "docs/design.md", 0.9d);
    AtomicReference<String> relationAnchor = new AtomicReference<>();
    ContextBuilder builder =
        builder(
            List.of(target),
            (workspaceId, objectId) -> {
              assertEquals(WORKSPACE_ID, workspaceId);
              relationAnchor.set(objectId);
              return completeRelations(
                  relation(
                      "relation:describes",
                      objectId,
                      RelationType.DESCRIBES,
                      design,
                      RetrievalFreshness.CURRENT_CANONICAL,
                      List.of(relationEvidence)));
            });

    EngineeringContext context = builder.build(request(false));

    assertTrue(context.complete());
    assertEquals("symbol:builder", relationAnchor.get());
    assertEquals(List.of("symbol:builder", "design:context"), objectIds(context));
    assertEquals(ContextSelectionReason.TARGET, context.items().get(0).selectionReason());
    assertEquals(
        ContextSelectionReason.DIRECT_REQUIREMENT_DESIGN_DECISION,
        context.items().get(1).selectionReason());
    assertEquals(List.of(relationEvidence), context.items().get(1).provenance().evidence());
  }

  @Test
  void rejectsEvidenceLessDirectRelation() {
    RetrievalItem target = target("symbol:builder", RetrievalFreshness.CURRENT_CANONICAL);
    KnowledgeObject design = canonicalObject("design:unsupported", ObjectType.DESIGN, "Design");
    ContextBuilder builder =
        builder(
            List.of(target),
            (workspaceId, objectId) ->
                completeRelations(
                    relation(
                        "relation:no-evidence",
                        objectId,
                        RelationType.DESCRIBES,
                        design,
                        RetrievalFreshness.CURRENT_CANONICAL,
                        List.of())));

    EngineeringContext context = builder.build(request(false));

    assertEquals(List.of("symbol:builder"), objectIds(context));
    assertTrue(
        context.omissions().stream()
            .anyMatch(
                omission ->
                    omission.reason() == OmissionReason.MISSING_EVIDENCE
                        && omission.objectId().equals(Optional.of("design:unsupported"))));
  }

  @Test
  void prefersCurrentTargetAndCurrentRelatedObjectOverStaleAlternatives() {
    RetrievalItem staleTarget = target("symbol:stale", RetrievalFreshness.STALE);
    RetrievalItem currentTarget = target("symbol:current", RetrievalFreshness.CURRENT_DERIVED);
    KnowledgeObject staleDesign = canonicalObject("design:shared", ObjectType.DESIGN, "Old design");
    KnowledgeObject currentDesign =
        canonicalObject("design:shared", ObjectType.DESIGN, "Current design");
    Evidence evidence = evidence(EvidenceType.SOURCE_TEXT, "docs/design.md", 0.8d);
    ContextBuilder builder =
        builder(
            List.of(staleTarget, currentTarget),
            (workspaceId, objectId) ->
                completeRelations(
                    relation(
                        "relation:stale",
                        objectId,
                        RelationType.DESCRIBES,
                        staleDesign,
                        RetrievalFreshness.STALE,
                        List.of(evidence)),
                    relation(
                        "relation:current",
                        objectId,
                        RelationType.DESCRIBES,
                        currentDesign,
                        RetrievalFreshness.CURRENT_CANONICAL,
                        List.of(evidence))));

    EngineeringContext context = builder.build(request(true));

    assertEquals(List.of("symbol:current", "design:shared"), objectIds(context));
    assertEquals(RetrievalFreshness.CURRENT_CANONICAL, context.items().get(1).freshness());
    assertEquals("Current design", context.items().get(1).fragment());
    assertEquals(
        2,
        context.omissions().stream()
            .filter(omission -> omission.reason() == OmissionReason.STALE)
            .count());
  }

  @Test
  void attachesGitEnrichmentToTheLocatedTargetWithoutCreatingAnotherItem() {
    Evidence gitEvidence =
        evidence(EvidenceType.VERSION_CONTROL, "commit:abc123", 0.95d);
    RetrievalItem target =
        retrievalItem(
            "symbol:builder",
            ObjectType.CODE_SYMBOL,
            RetrievalFreshness.CURRENT_CANONICAL,
            Set.of(RetrievalSource.STRUCTURED_STORE, RetrievalSource.GIT_EVIDENCE),
            List.of(gitEvidence));
    ContextBuilder builder = builder(List.of(target), (workspaceId, objectId) -> completeRelations());

    EngineeringContext context = builder.build(request(false));

    assertEquals(1, context.items().size());
    ContextProvenance provenance = context.items().get(0).provenance();
    assertTrue(provenance.contributingSources().contains(RetrievalSource.GIT_EVIDENCE));
    assertEquals(List.of(gitEvidence), provenance.evidence());
    assertEquals(0.95d, provenance.confidence());
    assertFalse(context.items().stream().anyMatch(item -> item.objectType() == ObjectType.COMMIT));
  }

  private static ContextBuilder builder(
      List<RetrievalItem> items, ContextRelationReader relationReader) {
    return new DefaultContextBuilder(
        query -> new RetrievalResult(WORKSPACE_ID, items, List.of(), true), relationReader);
  }

  private static ContextRequest request(boolean includeStale) {
    return new ContextRequest(
        WORKSPACE_ID, "ContextBuilder", ContextIntent.UNDERSTAND, 4096, includeStale, true);
  }

  private static RetrievalItem target(String objectId, RetrievalFreshness freshness) {
    return retrievalItem(
        objectId,
        ObjectType.CODE_SYMBOL,
        freshness,
        Set.of(RetrievalSource.STRUCTURED_STORE),
        List.of());
  }

  private static RetrievalItem retrievalItem(
      String objectId,
      ObjectType objectType,
      RetrievalFreshness freshness,
      Set<RetrievalSource> sources,
      List<Evidence> evidence) {
    SourceRef source = SourceRef.of("repository", "src/ContextBuilder.java");
    KnowledgeClass knowledgeClass =
        freshness == RetrievalFreshness.CURRENT_CANONICAL
            ? KnowledgeClass.CANONICAL
            : KnowledgeClass.DERIVED;
    return new RetrievalItem(
        Optional.of(objectId),
        objectType,
        objectId,
        Optional.of("Target implementation"),
        source,
        Optional.of(knowledgeClass),
        freshness,
        sources,
        evidence,
        new RankExplanation(
            freshness,
            RetrievalMatchCategory.EXACT_SYMBOL,
            true,
            0,
            "object:" + objectId,
            "fixed target fixture"));
  }

  private static KnowledgeObject canonicalObject(String id, ObjectType type, String content) {
    return KnowledgeObject.canonical(
        id,
        type,
        id,
        Optional.of(content),
        SourceRef.of("repository", "docs/" + id.replace(':', '-') + ".md"),
        Map.of(),
        "hash:" + id);
  }

  private static Evidence evidence(EvidenceType type, String locator, double confidence) {
    return new Evidence(type, SourceRef.of("repository", locator), locator, confidence);
  }

  private static DirectContextRelation relation(
      String relationId,
      String anchorObjectId,
      RelationType type,
      KnowledgeObject relatedObject,
      RetrievalFreshness freshness,
      List<Evidence> evidence) {
    return new DirectContextRelation(
        WORKSPACE_ID,
        relationId,
        anchorObjectId,
        anchorObjectId,
        type,
        relatedObject.id(),
        relatedObject,
        freshness,
        evidence);
  }

  private static ContextRelationReadResult completeRelations(DirectContextRelation... relations) {
    return new ContextRelationReadResult(List.of(relations), List.of(), true);
  }

  private static List<String> objectIds(EngineeringContext context) {
    return context.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
