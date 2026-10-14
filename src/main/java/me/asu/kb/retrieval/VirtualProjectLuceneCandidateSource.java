package me.asu.kb.retrieval;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProject;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectIndexStatus;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProjectSyncStatus;

/** Bounded Lucene recall from the one selected virtual-project index. */
final class VirtualProjectLuceneCandidateSource implements CandidateSource {
  private final SqliteKnowledgeStore store;
  private final VirtualProjectIndexManager indexManager;

  VirtualProjectLuceneCandidateSource(
      SqliteKnowledgeStore store, VirtualProjectIndexManager indexManager) {
    this.store = Objects.requireNonNull(store, "store");
    this.indexManager = Objects.requireNonNull(indexManager, "indexManager");
  }

  @Override
  public RetrievalSource source() {
    return RetrievalSource.VIRTUAL_PROJECT_LUCENE;
  }

  @Override
  public CandidateBatch collect(ValidatedKnowledgeQuery query) {
    String projectId = query.virtualProjectId();
    if (projectId.isBlank()) {
      return new CandidateBatch(source(), List.of(), List.of(), false);
    }

    final VirtualProject project;
    try {
      project = store.findVirtualProject(projectId).orElse(null);
    } catch (SQLException failure) {
      return failed("selected virtual project is unavailable");
    }
    if (project == null) {
      return failed("selected virtual project is unavailable");
    }

    List<RetrievalDiagnostic> diagnostics = new ArrayList<>();
    if (project.indexStatus() == VirtualProjectIndexStatus.NOT_BUILT) {
      diagnostics.add(diagnostic(RetrievalDiagnosticCode.SOURCE_UNAVAILABLE,
          "selected virtual project index is not built"));
      return new CandidateBatch(source(), List.of(), diagnostics, false);
    }
    if (project.indexStatus() != VirtualProjectIndexStatus.CURRENT) {
      diagnostics.add(diagnostic(RetrievalDiagnosticCode.SOURCE_UNAVAILABLE,
          "selected virtual project index is not current"));
    }
    if (project.syncStatus() == VirtualProjectSyncStatus.PARTIAL
        || project.syncStatus() == VirtualProjectSyncStatus.FAILED) {
      diagnostics.add(diagnostic(RetrievalDiagnosticCode.SOURCE_FAILED,
          "selected virtual project synchronization is incomplete"));
    }

    ValidatedKnowledgeQuery projectQuery =
        new ValidatedKnowledgeQuery(
            projectId,
            query.workspaceRoot(),
            query.text(),
            query.objectTypes(),
            query.includeStale(),
            query.requireEvidence(),
            query.budgets(),
            projectId);
    try (VirtualProjectIndexManager.ProjectIndexLease lease = indexManager.acquire(projectId)) {
      List<EngineeringKnowledgeLuceneProjection.LexicalHit> hits = lease.search(projectQuery);
      List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
      for (EngineeringKnowledgeLuceneProjection.LexicalHit hit : hits) {
        KnowledgeClass knowledgeClass;
        try {
          knowledgeClass = KnowledgeClass.valueOf(hit.knowledgeClass());
        } catch (IllegalArgumentException malformedHit) {
          diagnostics.add(diagnostic(RetrievalDiagnosticCode.UNMAPPABLE_CANDIDATE,
              "virtual project candidate has invalid knowledge metadata"));
          continue;
        }
        boolean stale = "STALE".equals(hit.lifecycle());
        RetrievalFreshness freshness =
            stale
                ? RetrievalFreshness.STALE
                : knowledgeClass == KnowledgeClass.CANONICAL
                    ? RetrievalFreshness.CURRENT_CANONICAL
                    : RetrievalFreshness.CURRENT_DERIVED;
        if (stale && !query.includeStale()) {
          diagnostics.add(diagnostic(RetrievalDiagnosticCode.STALE_CANDIDATE,
              "virtual project candidate is stale"));
          continue;
        }
        candidates.add(
            new RetrievalCandidate(
                Optional.of(hit.objectId()),
                hit.objectType(),
                hit.title(),
                hit.content().map(VirtualProjectLuceneCandidateSource::excerpt),
                hit.source(),
                Optional.of(knowledgeClass),
                freshness,
                RetrievalMatchCategory.BM25_LEXICAL,
                source(),
                candidates.size(),
                true,
                List.of(),
                Optional.of(hit.inputContentHash()),
                Optional.of(hit.projectionVersion()),
                Optional.of(projectId)));
      }
      boolean truncated = hits.size() == query.budgets().luceneCandidates();
      if (truncated) {
        diagnostics.add(diagnostic(RetrievalDiagnosticCode.SOURCE_TRUNCATED,
            "virtual project candidate budget reached"));
      }
      return new CandidateBatch(source(), candidates, diagnostics, truncated);
    } catch (Exception failure) {
      diagnostics.add(diagnostic(RetrievalDiagnosticCode.SOURCE_FAILED,
          "selected virtual project index could not be queried"));
      return new CandidateBatch(source(), List.of(), diagnostics, false);
    }
  }

  private CandidateBatch failed(String detail) {
    return new CandidateBatch(source(), List.of(),
        List.of(diagnostic(RetrievalDiagnosticCode.SOURCE_UNAVAILABLE, detail)), false);
  }

  private RetrievalDiagnostic diagnostic(RetrievalDiagnosticCode code, String detail) {
    return new RetrievalDiagnostic(Optional.of(source()), code, detail);
  }

  private static String excerpt(String content) {
    return content.length() <= 240 ? content : content.substring(0, 240);
  }
}
