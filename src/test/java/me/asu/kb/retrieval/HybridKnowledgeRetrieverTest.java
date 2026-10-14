package me.asu.kb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridKnowledgeRetrieverTest {
  private static final String WORKSPACE_ID = "fixture";

  @TempDir Path tempDir;

  @Test
  void rejectsInvalidQueriesBeforeInvokingCandidateSources() {
    AtomicInteger invocations = new AtomicInteger();
    CandidateSource source =
        fixedSource(
            RetrievalSource.STRUCTURED_STORE,
            invocations,
            List.of(candidate("unused", RetrievalMatchCategory.STRUCTURED_OBJECT, 0)));
    KnowledgeRetriever retriever = retriever(source);

    RetrievalResult blank = retriever.retrieve(KnowledgeQuery.of(WORKSPACE_ID, "  "));
    RetrievalResult unknown = retriever.retrieve(KnowledgeQuery.of("unknown", "symbol"));
    RetrievalResult excessive =
        retriever.retrieve(
            new KnowledgeQuery(WORKSPACE_ID, "symbol", Set.of(), 101, false, false));

    assertEquals(0, invocations.get());
    assertEquals(RetrievalDiagnosticCode.INVALID_QUERY, blank.diagnostics().get(0).code());
    assertEquals(RetrievalDiagnosticCode.UNKNOWN_WORKSPACE, unknown.diagnostics().get(0).code());
    assertEquals(RetrievalDiagnosticCode.LIMIT_EXCEEDED, excessive.diagnostics().get(0).code());
    assertFalse(blank.complete());
  }

  @Test
  void readsACommittedCanonicalObjectThroughThePublicRetrieveSeam() throws Exception {
    Path database = tempDir.resolve("fixture.sqlite");
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(database)) {
      KnowledgeObject object =
          KnowledgeObject.canonical(
              "doc:architecture",
              ObjectType.DOCUMENT,
              "Architecture Guide",
              Optional.of("Bounded hybrid retrieval"),
              SourceRef.of("repository", "docs/architecture.md"),
              Map.of("kind", "guide"),
              "document-hash");
      store.save(
          new ProjectionWrite(
              List.of(ObjectWrite.canonical(object)), List.of(), List.of()));
      KnowledgeRetriever retriever =
          retriever(new StructuralStoreCandidateSource(WORKSPACE_ID, store));

      RetrievalResult result = retriever.retrieve(KnowledgeQuery.of(WORKSPACE_ID, "Architecture"));

      assertTrue(result.complete());
      assertEquals(List.of("doc:architecture"), objectIds(result));
      assertEquals(RetrievalFreshness.CURRENT_CANONICAL, result.items().get(0).freshness());
      assertEquals(
          RetrievalMatchCategory.STRUCTURED_OBJECT,
          result.items().get(0).rankExplanation().matchCategory());
    }
  }

  @Test
  void ranksCurrentExactSymbolAheadOfCurrentBm25AndExplainsBothTiers() {
    CandidateSource symbols =
        fixedSource(
            RetrievalSource.CTAGS_SYMBOL,
            new AtomicInteger(),
            List.of(candidate("symbol:parse", RetrievalMatchCategory.EXACT_SYMBOL, 7)));
    CandidateSource lexical =
        fixedSource(
            RetrievalSource.LUCENE_BM25,
            new AtomicInteger(),
            List.of(candidate("doc:parse", RetrievalMatchCategory.BM25_LEXICAL, 0)));

    RetrievalResult result =
        retriever(symbols, lexical).retrieve(KnowledgeQuery.of(WORKSPACE_ID, "parse"));

    assertTrue(result.complete());
    assertEquals(List.of("symbol:parse", "doc:parse"), objectIds(result));
    assertEquals(
        RetrievalMatchCategory.EXACT_SYMBOL,
        result.items().get(0).rankExplanation().matchCategory());
    assertEquals(
        RetrievalMatchCategory.BM25_LEXICAL,
        result.items().get(1).rankExplanation().matchCategory());
    assertTrue(result.items().get(0).rankExplanation().summary().contains("EXACT_SYMBOL"));
  }

  @Test
  void givesSelectedProjectCandidatesDeterministicPreferenceAndPreservesIdentity() {
    CandidateSource shared =
        fixedSource(
            RetrievalSource.LUCENE_BM25,
            new AtomicInteger(),
            List.of(candidate("shared:parse", RetrievalMatchCategory.BM25_LEXICAL, 0)));
    CandidateSource project =
        fixedSource(
            RetrievalSource.VIRTUAL_PROJECT_LUCENE,
            new AtomicInteger(),
            List.of(projectCandidate("project:parse", "project-a")));

    RetrievalResult result =
        retriever(shared, project)
            .retrieve(
                new KnowledgeQuery(
                    WORKSPACE_ID, "parse", Set.of(), 10, false, false, "project-a"));

    assertTrue(result.complete());
    assertEquals(List.of("project:parse", "shared:parse"), objectIds(result));
    assertEquals(Optional.of("project-a"), result.items().get(0).virtualProjectId());
    assertEquals(Optional.empty(), result.items().get(1).virtualProjectId());
  }

  @Test
  void usesStableIdentityFallbackWhenAllEarlierRankTiersTie() {
    CandidateSource source =
        fixedSource(
            RetrievalSource.STRUCTURED_STORE,
            new AtomicInteger(),
            List.of(
                candidate("object:b", RetrievalMatchCategory.STRUCTURED_OBJECT, 0),
                candidate("object:a", RetrievalMatchCategory.STRUCTURED_OBJECT, 0)));

    RetrievalResult first = retriever(source).retrieve(KnowledgeQuery.of(WORKSPACE_ID, "object"));
    RetrievalResult second = retriever(source).retrieve(KnowledgeQuery.of(WORKSPACE_ID, "object"));

    assertEquals(List.of("object:a", "object:b"), objectIds(first));
    assertEquals(objectIds(first), objectIds(second));
    assertEquals("object:object:a", first.items().get(0).rankExplanation().stableFallbackKey());
  }

  private KnowledgeRetriever retriever(CandidateSource... sources) {
    return new HybridKnowledgeRetriever(
        new KnowledgeQueryValidator(Map.of(WORKSPACE_ID, tempDir)),
        List.of(sources),
        GitEvidenceSource.disabled());
  }

  private static CandidateSource fixedSource(
      RetrievalSource source, AtomicInteger invocations, List<RetrievalCandidate> candidates) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return source;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        invocations.incrementAndGet();
        return new CandidateBatch(source, candidates, List.of(), false);
      }
    };
  }

  private static RetrievalCandidate candidate(
      String objectId, RetrievalMatchCategory matchCategory, int sourceRank) {
    RetrievalSource source =
        matchCategory == RetrievalMatchCategory.EXACT_SYMBOL
            ? RetrievalSource.CTAGS_SYMBOL
            : matchCategory == RetrievalMatchCategory.BM25_LEXICAL
                ? RetrievalSource.LUCENE_BM25
                : RetrievalSource.STRUCTURED_STORE;
    return new RetrievalCandidate(
        Optional.of(objectId),
        matchCategory == RetrievalMatchCategory.EXACT_SYMBOL
            ? ObjectType.CODE_SYMBOL
            : ObjectType.DOCUMENT,
        objectId,
        Optional.of("fixture content"),
        SourceRef.of("engineering-workspace", WORKSPACE_ID + ":src/Fixture.java"),
        Optional.of(KnowledgeClass.DERIVED),
        RetrievalFreshness.CURRENT_DERIVED,
        matchCategory,
        source,
        sourceRank,
        true,
        List.of(),
        Optional.of("input-hash"),
        Optional.of("fixture-v1"));
  }

  private static RetrievalCandidate projectCandidate(String objectId, String projectId) {
    return new RetrievalCandidate(
        Optional.of(objectId),
        ObjectType.DOCUMENT,
        objectId,
        Optional.of("project content"),
        SourceRef.of("virtual-project", "docs/" + objectId),
        Optional.of(KnowledgeClass.CANONICAL),
        RetrievalFreshness.CURRENT_CANONICAL,
        RetrievalMatchCategory.BM25_LEXICAL,
        RetrievalSource.VIRTUAL_PROJECT_LUCENE,
        0,
        true,
        List.of(),
        Optional.of("project-hash"),
        Optional.of("project-index-v1"),
        Optional.of(projectId));
  }

  private static List<String> objectIds(RetrievalResult result) {
    return result.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
