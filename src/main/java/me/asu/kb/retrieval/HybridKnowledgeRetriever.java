package me.asu.kb.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Default bounded coordinator behind the single public retrieval seam. */
final class HybridKnowledgeRetriever implements KnowledgeRetriever {
  private final KnowledgeQueryValidator validator;
  private final List<CandidateSource> candidateSources;
  private final GitEvidenceSource gitEvidenceSource;
  private final CandidateFusionPreprocessor preprocessor = new CandidateFusionPreprocessor();
  private final TieredCandidateRanker ranker = new TieredCandidateRanker();

  HybridKnowledgeRetriever(
      KnowledgeQueryValidator validator,
      List<CandidateSource> candidateSources,
      GitEvidenceSource gitEvidenceSource) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.candidateSources =
        List.copyOf(Objects.requireNonNull(candidateSources, "candidateSources"));
    if (this.candidateSources.isEmpty() || this.candidateSources.size() > 5) {
      throw new IllegalArgumentException("between one and five candidate sources are required");
    }
    this.gitEvidenceSource = Objects.requireNonNull(gitEvidenceSource, "gitEvidenceSource");
  }

  @Override
  public RetrievalResult retrieve(KnowledgeQuery request) {
    QueryValidation validation = validator.validate(request);
    if (validation.query().isEmpty()) {
      String workspaceId = request == null ? "" : request.workspaceId();
      return new RetrievalResult(workspaceId, List.of(), validation.diagnostics(), false);
    }
    ValidatedKnowledgeQuery query = validation.query().orElseThrow();
    long deadline = System.nanoTime() + query.budgets().deadline().toNanos();
    List<CandidateBatch> batches = collectSources(query, deadline);
    List<String> matchedPaths = matchedPaths(batches, query.budgets().gitPaths());
    GitEvidenceEnrichment git = collectGit(query, matchedPaths, deadline);
    CandidatePreparation prepared = preprocessor.prepare(query, batches, git);

    List<RetrievalDiagnostic> diagnostics = new ArrayList<>(prepared.diagnostics());
    List<FusedCandidate> eligible = prepared.candidates();
    if (query.requireEvidence()) {
      int before = eligible.size();
      eligible = eligible.stream().filter(candidate -> !candidate.evidence().isEmpty()).toList();
      if (eligible.size() != before) {
        diagnostics.add(
            new RetrievalDiagnostic(
                Optional.empty(),
                RetrievalDiagnosticCode.PROVENANCE_INCOMPLETE,
                "candidates without evidence were excluded"));
      }
    }
    List<RetrievalItem> items = ranker.rank(eligible, query.budgets().resultLimit());
    boolean allSourcesFailed = allSourcesFailed(batches);
    if (allSourcesFailed && diagnostics.isEmpty()) {
      diagnostics.add(
          new RetrievalDiagnostic(
              Optional.empty(),
              RetrievalDiagnosticCode.SOURCE_FAILED,
              "all applicable candidate sources failed"));
    }
    boolean complete = !prepared.truncated() && diagnostics.isEmpty() && !allSourcesFailed;
    return new RetrievalResult(query.workspaceId(), items, diagnostics, complete);
  }

  private List<CandidateBatch> collectSources(ValidatedKnowledgeQuery query, long deadline) {
    if (candidateSources.isEmpty()) {
      return List.of();
    }
    ExecutorService executor = Executors.newFixedThreadPool(candidateSources.size());
    try {
      List<Future<CandidateBatch>> futures = new ArrayList<>(candidateSources.size());
      for (CandidateSource source : candidateSources) {
        futures.add(executor.submit(() -> source.collect(query)));
      }
      List<CandidateBatch> batches = new ArrayList<>(candidateSources.size());
      for (int index = 0; index < futures.size(); index++) {
        CandidateSource source = candidateSources.get(index);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          futures.get(index).cancel(true);
          batches.add(failed(source.source(), "candidate source exceeded the query deadline"));
          continue;
        }
        try {
          batches.add(futures.get(index).get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException timeout) {
          futures.get(index).cancel(true);
          batches.add(failed(source.source(), "candidate source exceeded the query deadline"));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          batches.add(failed(source.source(), "retrieval was interrupted"));
        } catch (ExecutionException failure) {
          batches.add(failed(source.source(), "candidate source failed"));
        }
      }
      return List.copyOf(batches);
    } finally {
      executor.shutdownNow();
    }
  }

  private GitEvidenceEnrichment collectGit(
      ValidatedKnowledgeQuery query, List<String> paths, long deadline) {
    if (paths.isEmpty()) {
      return new GitEvidenceEnrichment(java.util.Map.of(), List.of(), false);
    }
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      return failedGit("Git enrichment skipped because the query deadline expired");
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<GitEvidenceEnrichment> future =
          executor.submit(() -> gitEvidenceSource.enrich(query, paths));
      try {
        return future.get(remaining, TimeUnit.NANOSECONDS);
      } catch (TimeoutException timeout) {
        future.cancel(true);
        return failedGit("Git enrichment exceeded the query deadline");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return failedGit("Git enrichment was interrupted");
      } catch (ExecutionException failure) {
        return failedGit("Git enrichment failed");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static List<String> matchedPaths(List<CandidateBatch> batches, int limit) {
    Set<String> paths = new LinkedHashSet<>();
    for (CandidateBatch batch : batches) {
      for (RetrievalCandidate candidate : batch.candidates()) {
        if (!candidate.source().system().equals("engineering-workspace")) {
          continue;
        }
        String locator = candidate.source().locator();
        int separator = locator.indexOf(':');
        String path = separator >= 0 ? locator.substring(separator + 1) : locator;
        if (!path.isBlank()) {
          paths.add(path);
        }
        if (paths.size() == limit) {
          return List.copyOf(paths);
        }
      }
    }
    return List.copyOf(paths);
  }

  private static boolean allSourcesFailed(List<CandidateBatch> batches) {
    return !batches.isEmpty()
        && batches.stream()
            .allMatch(
                batch ->
                    batch.candidates().isEmpty()
                        && batch.diagnostics().stream()
                            .anyMatch(
                                diagnostic ->
                                    diagnostic.code() == RetrievalDiagnosticCode.SOURCE_FAILED
                                        || diagnostic.code()
                                            == RetrievalDiagnosticCode.SOURCE_UNAVAILABLE));
  }

  private static CandidateBatch failed(RetrievalSource source, String detail) {
    return new CandidateBatch(
        source,
        List.of(),
        List.of(
            new RetrievalDiagnostic(
                Optional.of(source), RetrievalDiagnosticCode.SOURCE_FAILED, detail)),
        false);
  }

  private static GitEvidenceEnrichment failedGit(String detail) {
    return new GitEvidenceEnrichment(
        java.util.Map.of(),
        List.of(
            new RetrievalDiagnostic(
                Optional.of(RetrievalSource.GIT_EVIDENCE),
                RetrievalDiagnosticCode.SOURCE_FAILED,
                detail)),
        false);
  }
}
