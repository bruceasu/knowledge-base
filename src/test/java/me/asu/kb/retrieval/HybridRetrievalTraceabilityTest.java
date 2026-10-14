package me.asu.kb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridRetrievalTraceabilityTest {
  private static final String WORKSPACE_ID = "traceability";

  @TempDir Path tempDir;

  @Test
  void returnsOnlyCurrentTraceableLuceneHitsAndDiagnosesChangedStoreMappings() throws Exception {
    try (SqliteKnowledgeStore store = new SqliteKnowledgeStore(tempDir.resolve("knowledge.sqlite"));
        EngineeringKnowledgeLuceneProjection projection =
            new EngineeringKnowledgeLuceneProjection(tempDir.resolve("engineering-index"))) {
      KnowledgeObject current = canonical("current", "docs/current.md", "current-hash");
      KnowledgeObject stale = canonical("stale", "docs/stale.md", "stale-hash-v1");
      KnowledgeObject unmappable = canonical("unmappable", "docs/old.md", "mapping-hash");
      save(store, current, stale, unmappable);
      projection.rebuildFromStore(WORKSPACE_ID, store);

      save(
          store,
          canonical("stale", "docs/stale.md", "stale-hash-v2"),
          canonical("unmappable", "docs/new.md", "mapping-hash"));
      KnowledgeRetriever retriever =
          retriever(new LuceneBm25LexicalRetriever(projection, store));

      RetrievalResult result = retriever.retrieve(KnowledgeQuery.of(WORKSPACE_ID, "Needle"));

      assertFalse(result.complete());
      assertEquals(List.of("current"), objectIds(result));
      RetrievalItem item = result.items().get(0);
      assertEquals(Set.of(RetrievalSource.LUCENE_BM25), item.contributingSources());
      assertEquals(RetrievalMatchCategory.BM25_LEXICAL, item.rankExplanation().matchCategory());
      assertEquals(RetrievalFreshness.CURRENT_CANONICAL, item.freshness());
      assertTrue(hasDiagnostic(result, RetrievalDiagnosticCode.STALE_CANDIDATE));
      assertTrue(hasDiagnostic(result, RetrievalDiagnosticCode.UNMAPPABLE_CANDIDATE));
    }
  }

  @Test
  void mergesSameObjectAcrossSourcesPrefersCanonicalAndAggregatesDistinctEvidence() {
    Evidence structuralEvidence = evidence("docs/shared.md", "line:3");
    Evidence lexicalEvidence = evidence("docs/shared.md", "line:8");
    RetrievalCandidate derived =
        candidate(
            "shared",
            "Shared derived",
            "docs/shared.md",
            KnowledgeClass.DERIVED,
            RetrievalFreshness.CURRENT_DERIVED,
            RetrievalSource.LUCENE_BM25,
            RetrievalMatchCategory.BM25_LEXICAL,
            lexicalEvidence);
    RetrievalCandidate canonical =
        candidate(
            "shared",
            "Shared canonical",
            "docs/shared.md",
            KnowledgeClass.CANONICAL,
            RetrievalFreshness.CURRENT_CANONICAL,
            RetrievalSource.STRUCTURED_STORE,
            RetrievalMatchCategory.STRUCTURED_OBJECT,
            structuralEvidence);

    RetrievalResult result =
        retriever(
                fixedSource(RetrievalSource.LUCENE_BM25, derived),
                fixedSource(RetrievalSource.STRUCTURED_STORE, canonical))
            .retrieve(KnowledgeQuery.of(WORKSPACE_ID, "Shared"));

    assertTrue(result.complete());
    assertEquals(1, result.items().size());
    RetrievalItem item = result.items().get(0);
    assertEquals("Shared canonical", item.title());
    assertEquals(KnowledgeClass.CANONICAL, item.knowledgeClass().orElseThrow());
    assertEquals(RetrievalFreshness.CURRENT_CANONICAL, item.freshness());
    assertEquals(
        Set.of(RetrievalSource.STRUCTURED_STORE, RetrievalSource.LUCENE_BM25),
        item.contributingSources());
    assertEquals(List.of(lexicalEvidence, structuralEvidence), item.evidence());
    assertEquals(RetrievalMatchCategory.STRUCTURED_OBJECT, item.rankExplanation().matchCategory());
  }

  @Test
  void keepsTitleOnlyLookalikesAsSeparateResults() {
    RetrievalCandidate first =
        candidate(
            "first",
            "Duplicate title",
            "docs/first.md",
            KnowledgeClass.CANONICAL,
            RetrievalFreshness.CURRENT_CANONICAL,
            RetrievalSource.STRUCTURED_STORE,
            RetrievalMatchCategory.STRUCTURED_OBJECT,
            evidence("docs/first.md", "line:1"));
    RetrievalCandidate second =
        candidate(
            "second",
            "Duplicate title",
            "docs/second.md",
            KnowledgeClass.CANONICAL,
            RetrievalFreshness.CURRENT_CANONICAL,
            RetrievalSource.LUCENE_BM25,
            RetrievalMatchCategory.BM25_LEXICAL,
            evidence("docs/second.md", "line:1"));

    RetrievalResult result =
        retriever(
                fixedSource(RetrievalSource.STRUCTURED_STORE, first),
                fixedSource(RetrievalSource.LUCENE_BM25, second))
            .retrieve(KnowledgeQuery.of(WORKSPACE_ID, "Duplicate title"));

    assertTrue(result.complete());
    assertEquals(List.of("first", "second"), objectIds(result));
  }

  private KnowledgeRetriever retriever(CandidateSource... sources) {
    return new HybridKnowledgeRetriever(
        new KnowledgeQueryValidator(Map.of(WORKSPACE_ID, tempDir)),
        List.of(sources),
        GitEvidenceSource.disabled());
  }

  private static CandidateSource fixedSource(
      RetrievalSource source, RetrievalCandidate... candidates) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return source;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        return new CandidateBatch(source, List.of(candidates), List.of(), false);
      }
    };
  }

  private static RetrievalCandidate candidate(
      String objectId,
      String title,
      String path,
      KnowledgeClass knowledgeClass,
      RetrievalFreshness freshness,
      RetrievalSource retrievalSource,
      RetrievalMatchCategory matchCategory,
      Evidence evidence) {
    return new RetrievalCandidate(
        Optional.of(objectId),
        ObjectType.DOCUMENT,
        title,
        Optional.of("same excerpt"),
        SourceRef.of("repository", path),
        Optional.of(knowledgeClass),
        freshness,
        matchCategory,
        retrievalSource,
        0,
        true,
        List.of(evidence),
        Optional.of("input-hash"),
        Optional.of("projection-v1"));
  }

  private static KnowledgeObject canonical(String id, String path, String hash) {
    return KnowledgeObject.canonical(
        id,
        ObjectType.DOCUMENT,
        "Needle " + id,
        Optional.of("Needle content for " + id),
        SourceRef.of("repository", path),
        Map.of(),
        hash);
  }

  private static Evidence evidence(String path, String location) {
    return new Evidence(
        EvidenceType.SOURCE_TEXT, SourceRef.of("repository", path), location, 1.0d);
  }

  private static void save(SqliteKnowledgeStore store, KnowledgeObject... objects) throws Exception {
    store.save(
        new ProjectionWrite(
            java.util.Arrays.stream(objects).map(ObjectWrite::canonical).toList(),
            List.of(),
            List.of()));
  }

  private static boolean hasDiagnostic(
      RetrievalResult result, RetrievalDiagnosticCode diagnosticCode) {
    return result.diagnostics().stream().anyMatch(item -> item.code() == diagnosticCode);
  }

  private static List<String> objectIds(RetrievalResult result) {
    return result.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
