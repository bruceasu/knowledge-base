package me.asu.kb.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.adapter.codesearch.CodeSearchMatch;
import me.asu.kb.adapter.codesearch.CodeSearchResult;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitEvidenceResult;
import me.asu.kb.adapter.gitevidence.GitFileChange;
import me.asu.kb.adapter.symbol.ExtractedSymbol;
import me.asu.kb.adapter.symbol.VerifiedSymbolExtraction;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;

/** Stops Adapter result types at the retrieval boundary and emits normalized values only. */
final class AdapterCandidateNormalizer {
  CandidateBatch normalizeRipgrep(CodeSearchResult result, ValidatedKnowledgeQuery query) {
    int limit = Math.min(query.budgets().ripgrepCandidates(), result.matches().size());
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (CodeSearchMatch match : result.matches().subList(0, limit)) {
      candidates.add(
          new RetrievalCandidate(
              Optional.empty(),
              ObjectType.OTHER,
              match.relativePath(),
              Optional.of(match.excerpt()),
              match.evidence().source(),
              Optional.empty(),
              RetrievalFreshness.UNVERIFIED,
              RetrievalMatchCategory.EXACT_TEXT,
              RetrievalSource.RIPGREP_TEXT,
              candidates.size(),
              true,
              List.of(match.evidence()),
              Optional.empty(),
              Optional.empty()));
    }
    return batch(
        RetrievalSource.RIPGREP_TEXT,
        candidates,
        !result.complete() || result.matches().size() > limit,
        result.diagnostics().isEmpty() ? "" : "ripgrep returned diagnostics");
  }

  CandidateBatch normalizeCtags(VerifiedSymbolExtraction extraction, ValidatedKnowledgeQuery query) {
    int limit = Math.min(query.budgets().ctagsCandidates(), extraction.result().symbols().size());
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (ExtractedSymbol symbol : extraction.result().symbols().subList(0, limit)) {
      String hash = extraction.sourceContentHashes().get(symbol.relativePath());
      boolean exact =
          symbol.simpleName().equalsIgnoreCase(query.text())
              || symbol.qualifiedName().map(value -> value.equalsIgnoreCase(query.text())).orElse(false);
      candidates.add(
          new RetrievalCandidate(
              Optional.empty(),
              ObjectType.CODE_SYMBOL,
              symbol.qualifiedName().orElse(symbol.simpleName()),
              symbol.signature(),
              symbol.evidence().source(),
              Optional.of(KnowledgeClass.DERIVED),
              RetrievalFreshness.UNVERIFIED,
              exact ? RetrievalMatchCategory.EXACT_SYMBOL : RetrievalMatchCategory.STRUCTURED_OBJECT,
              RetrievalSource.CTAGS_SYMBOL,
              candidates.size(),
              hash != null,
              List.of(symbol.evidence()),
              Optional.ofNullable(hash),
              Optional.of(extraction.result().profile().profileId())));
    }
    return batch(
        RetrievalSource.CTAGS_SYMBOL,
        candidates,
        !extraction.result().complete() || extraction.result().symbols().size() > limit,
        extraction.result().diagnostics().isEmpty() ? "" : "Ctags returned diagnostics");
  }

  GitEvidenceEnrichment normalizeGit(GitEvidenceResult result, ValidatedKnowledgeQuery query) {
    Map<String, List<me.asu.kb.core.knowledge.Evidence>> byPath = new LinkedHashMap<>();
    int pathCount = 0;
    boolean truncated = !result.complete();
    outer:
    for (GitCommitEvidence commit : result.commits()) {
      for (GitFileChange change : commit.changes()) {
        for (String path : changedPaths(change)) {
          if (!byPath.containsKey(path) && pathCount >= query.budgets().gitPaths()) {
            truncated = true;
            break outer;
          }
          if (!byPath.containsKey(path)) {
            pathCount++;
          }
          byPath.computeIfAbsent(path, ignored -> new ArrayList<>()).add(change.evidence());
        }
      }
    }
    List<RetrievalDiagnostic> diagnostics =
        diagnostics(
            RetrievalSource.GIT_EVIDENCE,
            truncated,
            result.diagnostics().isEmpty() ? "" : "Git evidence returned diagnostics");
    return new GitEvidenceEnrichment(byPath, diagnostics, truncated);
  }

  private static List<String> changedPaths(GitFileChange change) {
    List<String> paths = new ArrayList<>(2);
    change.oldPath().ifPresent(paths::add);
    change.newPath().filter(path -> !paths.contains(path)).ifPresent(paths::add);
    return paths;
  }

  private static CandidateBatch batch(
      RetrievalSource source,
      List<RetrievalCandidate> candidates,
      boolean truncated,
      String diagnosticDetail) {
    return new CandidateBatch(
        source, candidates, diagnostics(source, truncated, diagnosticDetail), truncated);
  }

  private static List<RetrievalDiagnostic> diagnostics(
      RetrievalSource source, boolean truncated, String diagnosticDetail) {
    List<RetrievalDiagnostic> diagnostics = new ArrayList<>();
    if (!diagnosticDetail.isEmpty()) {
      diagnostics.add(
          new RetrievalDiagnostic(
              Optional.of(source), RetrievalDiagnosticCode.SOURCE_FAILED, diagnosticDetail));
    }
    if (truncated) {
      diagnostics.add(
          new RetrievalDiagnostic(
              Optional.of(source), RetrievalDiagnosticCode.SOURCE_TRUNCATED, "source output is incomplete"));
    }
    return List.copyOf(diagnostics);
  }
}
