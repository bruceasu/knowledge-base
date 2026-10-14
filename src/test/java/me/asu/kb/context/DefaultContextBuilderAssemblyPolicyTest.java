package me.asu.kb.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.retrieval.RankExplanation;
import me.asu.kb.retrieval.RetrievalDiagnostic;
import me.asu.kb.retrieval.RetrievalDiagnosticCode;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalMatchCategory;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.retrieval.RetrievalSource;
import org.junit.jupiter.api.Test;

class DefaultContextBuilderAssemblyPolicyTest {
  private static final String WORKSPACE_ID = "fixture";

  @Test
  void ordersByFixedPriorityAndMergesOnlyStableIdentityWithAllEvidence() {
    RetrievalItem currentTarget = target("symbol:target", "target", RetrievalFreshness.CURRENT_CANONICAL);
    RetrievalItem staleTarget = target("symbol:old", "old", RetrievalFreshness.STALE);
    Evidence first = evidence(EvidenceType.SOURCE_TEXT, "design:first", 0.7d);
    Evidence second = evidence(EvidenceType.STATIC_ANALYSIS, "design:second", 0.9d);
    List<DirectContextRelation> relations =
        List.of(
            relation("other", ObjectType.DOCUMENT, RelationType.DESCRIBES),
            relation("commit", ObjectType.COMMIT, RelationType.DESCRIBES),
            relation("test", ObjectType.TEST, RelationType.VERIFIED_BY),
            relation("symbol", ObjectType.CODE_SYMBOL, RelationType.IMPLEMENTS),
            relation("design", ObjectType.DESIGN, RelationType.DESCRIBES, first),
            relation("design", ObjectType.DESIGN, RelationType.DESCRIBES, second));
    RetrievalDiagnostic sourceFailure =
        new RetrievalDiagnostic(
            Optional.of(RetrievalSource.RIPGREP_TEXT),
            RetrievalDiagnosticCode.SOURCE_FAILED,
            "fixed source failed");
    ContextBuilder builder =
        builder(
            new RetrievalResult(
                WORKSPACE_ID, List.of(staleTarget, currentTarget), List.of(sourceFailure), false),
            relations);

    EngineeringContext context = builder.build(request(8192, false));

    assertEquals(
        List.of(
            "symbol:target", "related:design", "related:symbol", "related:test",
            "related:commit", "related:other"),
        objectIds(context));
    ContextItem design = context.items().get(1);
    assertEquals(List.of(first, second), design.provenance().evidence());
    assertEquals(0.9d, design.provenance().confidence());
    assertTrue(hasOmission(context, OmissionReason.DUPLICATE_MERGED));
    assertTrue(hasOmission(context, OmissionReason.STALE));
    assertTrue(hasOmission(context, OmissionReason.SOURCE_FAILURE));
    assertFalse(context.complete());
  }

  @Test
  void reportsDeterministicPerItemAndTotalEstimates() {
    ContextBuilder builder =
        builder(completeRetrieval(target("symbol:target", "line1\r\nline2", RetrievalFreshness.CURRENT_CANONICAL)), List.of());

    EngineeringContext first = builder.build(request(8192, false));
    EngineeringContext second = builder.build(request(8192, false));

    ContextItem item = first.items().get(0);
    assertEquals("line1\nline2", item.fragment());
    assertEquals(
        ContextTokenEstimator.estimate(
            item.objectType(), item.objectId(), item.source(), item.sourceRange(), item.fragment()),
        item.estimatedTokens());
    assertEquals(item.estimatedTokens(), first.estimatedTokens());
    assertEquals(ContextTokenEstimator.VERSION, first.estimatorVersion());
    assertEquals(first.items(), second.items());
    assertEquals(first.estimatedTokens(), second.estimatedTokens());
  }

  @Test
  void rejectsBelowMinimumBudgetBeforeRetrieval() {
    AtomicInteger retrievalCalls = new AtomicInteger();
    ContextBuilder builder =
        new DefaultContextBuilder(
            query -> {
              retrievalCalls.incrementAndGet();
              return completeRetrieval(target("unused", "unused", RetrievalFreshness.CURRENT_CANONICAL));
            },
            (workspaceId, objectId) -> completeRelations());

    EngineeringContext context =
        builder.build(request(ContextTokenEstimator.MINIMUM_BUDGET - 1, false));

    assertEquals(0, retrievalCalls.get());
    assertTrue(context.items().isEmpty());
    assertEquals(ContextDiagnosticCode.INVALID_REQUEST, context.diagnostics().get(0).code());
  }

  @Test
  void reservesTargetThenTruncatesItAndOmitsLowerPriorityContentAtBoundary() {
    String targetContent = "目标🙂".repeat(200);
    RetrievalItem target =
        target("symbol:target", targetContent, RetrievalFreshness.CURRENT_CANONICAL);
    DirectContextRelation design = relation("design", ObjectType.DESIGN, RelationType.DESCRIBES);
    int budget = ContextTokenEstimator.MINIMUM_BUDGET;
    ContextBuilder builder = builder(completeRetrieval(target), List.of(design));

    EngineeringContext context = builder.build(request(budget, false));

    assertEquals("symbol:target", context.items().get(0).objectId().orElseThrow());
    assertTrue(context.items().get(0).truncated());
    assertTrue(targetContent.startsWith(context.items().get(0).fragment()));
    assertTrue(context.estimatedTokens() <= budget);
    assertEquals(
        context.items().stream().mapToInt(ContextItem::estimatedTokens).sum(),
        context.estimatedTokens());
    assertTrue(
        context.omissions().stream()
            .anyMatch(
                omission ->
                    omission.reason() == OmissionReason.BUDGET
                        && omission.objectId().equals(Optional.of("symbol:target"))));
    assertTrue(
        context.omissions().stream()
            .anyMatch(
                omission ->
                    omission.reason() == OmissionReason.BUDGET
                        && omission.objectId().equals(Optional.of("related:design"))));
    assertFalse(context.complete());
  }

  private static ContextBuilder builder(
      RetrievalResult retrieval, List<DirectContextRelation> relations) {
    return new DefaultContextBuilder(
        query -> retrieval,
        (workspaceId, objectId) ->
            new ContextRelationReadResult(relations, List.of(), true));
  }

  private static ContextRequest request(int budget, boolean includeStale) {
    return new ContextRequest(
        WORKSPACE_ID, "target", ContextIntent.UNDERSTAND, budget, includeStale, true);
  }

  private static RetrievalResult completeRetrieval(RetrievalItem target) {
    return new RetrievalResult(WORKSPACE_ID, List.of(target), List.of(), true);
  }

  private static RetrievalItem target(
      String objectId, String content, RetrievalFreshness freshness) {
    return new RetrievalItem(
        Optional.of(objectId),
        ObjectType.CODE_SYMBOL,
        objectId,
        Optional.of(content),
        SourceRef.of("repository", "src/Target.java"),
        Optional.of(
            freshness == RetrievalFreshness.CURRENT_CANONICAL
                ? KnowledgeClass.CANONICAL
                : KnowledgeClass.DERIVED),
        freshness,
        Set.of(RetrievalSource.STRUCTURED_STORE),
        List.of(),
        new RankExplanation(
            freshness,
            RetrievalMatchCategory.EXACT_SYMBOL,
            true,
            0,
            "object:" + objectId,
            "fixed target"));
  }

  private static DirectContextRelation relation(
      String suffix, ObjectType type, RelationType relationType, Evidence... evidence) {
    List<Evidence> evidenceList =
        evidence.length == 0
            ? List.of(evidence(EvidenceType.SOURCE_TEXT, suffix, 0.8d))
            : List.of(evidence);
    KnowledgeObject related =
        KnowledgeObject.canonical(
            "related:" + suffix,
            type,
            suffix,
            Optional.of("content:" + suffix),
            SourceRef.of("repository", "fixture/" + suffix),
            Map.of(),
            "hash:" + suffix);
    return new DirectContextRelation(
        WORKSPACE_ID,
        "relation:" + suffix + ":" + evidenceList.get(0).location(),
        "symbol:target",
        "symbol:target",
        relationType,
        related.id(),
        related,
        RetrievalFreshness.CURRENT_CANONICAL,
        evidenceList);
  }

  private static Evidence evidence(EvidenceType type, String location, double confidence) {
    return new Evidence(type, SourceRef.of("fixture", location), location, confidence);
  }

  private static ContextRelationReadResult completeRelations(DirectContextRelation... relations) {
    return new ContextRelationReadResult(List.of(relations), List.of(), true);
  }

  private static boolean hasOmission(EngineeringContext context, OmissionReason reason) {
    return context.omissions().stream().anyMatch(omission -> omission.reason() == reason);
  }

  private static List<String> objectIds(EngineeringContext context) {
    return context.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
