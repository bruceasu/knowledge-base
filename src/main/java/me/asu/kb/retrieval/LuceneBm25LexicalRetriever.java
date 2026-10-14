package me.asu.kb.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.store.SqliteKnowledgeStore;

/** BM25 recall Adapter that retains only source-local hit order. */
final class LuceneBm25LexicalRetriever implements CandidateSource {
  private final EngineeringKnowledgeLuceneProjection projection;
  private final SqliteKnowledgeStore store;
  private final BooleanSupplier enabled;

  LuceneBm25LexicalRetriever(
      EngineeringKnowledgeLuceneProjection projection, SqliteKnowledgeStore store) {
    this(projection, store, () -> true);
  }

  LuceneBm25LexicalRetriever(
      EngineeringKnowledgeLuceneProjection projection,
      SqliteKnowledgeStore store,
      BooleanSupplier enabled) {
    this.projection = Objects.requireNonNull(projection, "projection");
    this.store = Objects.requireNonNull(store, "store");
    this.enabled = Objects.requireNonNull(enabled, "enabled");
  }

  @Override
  public RetrievalSource source() {
    return RetrievalSource.LUCENE_BM25;
  }

  @Override
  public CandidateBatch collect(ValidatedKnowledgeQuery query) {
    if (!enabled.getAsBoolean()) {
      return unavailable();
    }
    try {
      List<EngineeringKnowledgeLuceneProjection.LexicalHit> hits = projection.search(query);
      List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
      List<RetrievalDiagnostic> diagnostics = new ArrayList<>();
      for (EngineeringKnowledgeLuceneProjection.LexicalHit hit : hits) {
        Optional<SqliteKnowledgeStore.StoredObject> resolved = store.findObject(hit.objectId());
        if (resolved.isEmpty()
            || !EngineeringKnowledgeLuceneProjection.SCHEMA_VERSION.equals(hit.projectionVersion())) {
          diagnostics.add(diagnostic(RetrievalDiagnosticCode.UNMAPPABLE_CANDIDATE));
          continue;
        }
        SqliteKnowledgeStore.StoredObject stored = resolved.orElseThrow();
        KnowledgeObject object = stored.object();
        String currentInputHash = object.inputContentHash().orElse(object.contentHash());
        boolean mappingMatches =
            object.type() == hit.objectType()
                && object.source().equals(hit.source())
                && object.knowledgeClass().name().equals(hit.knowledgeClass());
        if (!mappingMatches) {
          diagnostics.add(diagnostic(RetrievalDiagnosticCode.UNMAPPABLE_CANDIDATE));
          continue;
        }
        boolean stale =
            stored.lifecycleState() == SqliteKnowledgeStore.ObjectLifecycleState.STALE
                || "STALE".equals(hit.lifecycle())
                || !currentInputHash.equals(hit.inputContentHash());
        if (stale && !query.includeStale()) {
          diagnostics.add(diagnostic(RetrievalDiagnosticCode.STALE_CANDIDATE));
          continue;
        }
        KnowledgeClass knowledgeClass = object.knowledgeClass();
        RetrievalFreshness freshness =
            stale
                ? RetrievalFreshness.STALE
                : knowledgeClass == KnowledgeClass.CANONICAL
                    ? RetrievalFreshness.CURRENT_CANONICAL
                    : RetrievalFreshness.CURRENT_DERIVED;
        candidates.add(
            new RetrievalCandidate(
                Optional.of(object.id()),
                object.type(),
                object.title(),
                hit.content().map(LuceneBm25LexicalRetriever::excerpt),
                object.source(),
                Optional.of(knowledgeClass),
                freshness,
                RetrievalMatchCategory.BM25_LEXICAL,
                RetrievalSource.LUCENE_BM25,
                candidates.size(),
                true,
                List.of(),
                Optional.of(currentInputHash),
                Optional.of(hit.projectionVersion())));
      }
      boolean truncated = hits.size() == query.budgets().luceneCandidates();
      if (truncated) {
        diagnostics.add(
            new RetrievalDiagnostic(
                Optional.of(RetrievalSource.LUCENE_BM25),
                RetrievalDiagnosticCode.SOURCE_TRUNCATED,
                "Lucene candidate budget reached"));
      }
      return new CandidateBatch(RetrievalSource.LUCENE_BM25, candidates, diagnostics, truncated);
    } catch (Exception failure) {
      return new CandidateBatch(
          RetrievalSource.LUCENE_BM25,
          List.of(),
          List.of(
              new RetrievalDiagnostic(
                  Optional.of(RetrievalSource.LUCENE_BM25),
                  RetrievalDiagnosticCode.SOURCE_FAILED,
                  "engineering-knowledge Lucene query failed")),
          false);
    }
  }

  private static CandidateBatch unavailable() {
    return new CandidateBatch(
        RetrievalSource.LUCENE_BM25,
        List.of(),
        List.of(
            new RetrievalDiagnostic(
                Optional.of(RetrievalSource.LUCENE_BM25),
                RetrievalDiagnosticCode.SOURCE_UNAVAILABLE,
                "engineering-knowledge Lucene retrieval is disabled")),
        false);
  }

  private static RetrievalDiagnostic diagnostic(RetrievalDiagnosticCode code) {
    return new RetrievalDiagnostic(
        Optional.of(RetrievalSource.LUCENE_BM25),
        code,
        code == RetrievalDiagnosticCode.STALE_CANDIDATE
            ? "Lucene candidate does not match current Store input"
            : "Lucene candidate cannot be mapped to current Store knowledge");
  }

  private static String excerpt(String content) {
    return content.length() <= 240 ? content : content.substring(0, 240);
  }
}
