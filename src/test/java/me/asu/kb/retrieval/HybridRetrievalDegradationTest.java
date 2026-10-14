package me.asu.kb.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.adapter.codesearch.CodeSearchMatch;
import me.asu.kb.adapter.codesearch.CodeSearchRange;
import me.asu.kb.adapter.codesearch.CodeSearchResult;
import me.asu.kb.adapter.gitevidence.GitChangeKind;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitEvidenceResult;
import me.asu.kb.adapter.gitevidence.GitFileChange;
import me.asu.kb.adapter.gitevidence.GitToolProfile;
import me.asu.kb.adapter.symbol.CtagsToolProfile;
import me.asu.kb.adapter.symbol.ExtractedSymbol;
import me.asu.kb.adapter.symbol.SymbolExtractionResult;
import me.asu.kb.adapter.symbol.SymbolIdentity;
import me.asu.kb.adapter.symbol.SymbolKind;
import me.asu.kb.adapter.symbol.SymbolLanguage;
import me.asu.kb.adapter.symbol.SymbolSourceRange;
import me.asu.kb.adapter.symbol.VerifiedSymbolExtraction;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.KnowledgeClass;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridRetrievalDegradationTest {
  private static final String WORKSPACE_ID = "degradation";

  @TempDir Path tempDir;

  @Test
  void normalizesRipgrepCtagsAndGitWithinAssignedSourceBudgets() {
    ValidatedKnowledgeQuery query = boundedQuery();
    AdapterCandidateNormalizer normalizer = new AdapterCandidateNormalizer();

    CandidateBatch ripgrep =
        normalizer.normalizeRipgrep(
            new CodeSearchResult(
                List.of(codeMatch("src/First.java"), codeMatch("src/Second.java")),
                List.of(),
                true),
            query);
    CandidateBatch ctags =
        normalizer.normalizeCtags(
            new VerifiedSymbolExtraction(
                new SymbolExtractionResult(
                    List.of(symbol("First"), symbol("Second")),
                    new CtagsToolProfile("ctags-java-json-v1", Optional.of("Universal Ctags 6")),
                    List.of(),
                    true),
                Map.of(
                    "src/First.java", "a".repeat(64),
                    "src/Second.java", "b".repeat(64))),
            query);
    GitEvidenceEnrichment git = normalizer.normalizeGit(gitResult(), query);

    assertEquals(1, ripgrep.candidates().size());
    assertEquals(RetrievalMatchCategory.EXACT_TEXT, ripgrep.candidates().get(0).matchCategory());
    assertEquals(EvidenceType.SOURCE_TEXT, ripgrep.candidates().get(0).evidence().get(0).type());
    assertTrue(ripgrep.truncated());
    assertTrue(hasDiagnostic(ripgrep.diagnostics(), RetrievalDiagnosticCode.SOURCE_TRUNCATED));

    assertEquals(1, ctags.candidates().size());
    assertEquals(RetrievalMatchCategory.EXACT_SYMBOL, ctags.candidates().get(0).matchCategory());
    assertEquals(Optional.of("a".repeat(64)), ctags.candidates().get(0).inputContentHash());
    assertTrue(ctags.truncated());

    assertEquals(Set.of("src/First.java"), git.evidenceByPath().keySet());
    assertEquals(EvidenceType.VERSION_CONTROL, git.evidenceByPath().get("src/First.java").get(0).type());
    assertTrue(git.truncated());
    assertTrue(hasDiagnostic(git.diagnostics(), RetrievalDiagnosticCode.SOURCE_TRUNCATED));
  }

  @Test
  void returnsPartialResultsWhenOneCandidateSourceFails() {
    CandidateSource successful = fixedSource(candidate("available"));
    CandidateSource failed = throwingSource(RetrievalSource.LUCENE_BM25);

    RetrievalResult result = retriever(successful, failed).retrieve(KnowledgeQuery.of(WORKSPACE_ID, "available"));

    assertEquals(List.of("available"), objectIds(result));
    assertFalse(result.complete());
    assertTrue(hasDiagnostic(result.diagnostics(), RetrievalDiagnosticCode.SOURCE_FAILED));
  }

  @Test
  void returnsNoItemsAndExplicitDiagnosticsWhenAllCandidateSourcesFail() {
    RetrievalResult result =
        retriever(
                throwingSource(RetrievalSource.STRUCTURED_STORE),
                throwingSource(RetrievalSource.LUCENE_BM25))
            .retrieve(KnowledgeQuery.of(WORKSPACE_ID, "unavailable"));

    assertTrue(result.items().isEmpty());
    assertFalse(result.complete());
    assertEquals(2, result.diagnostics().size());
    assertTrue(
        result.diagnostics().stream()
            .allMatch(item -> item.code() == RetrievalDiagnosticCode.SOURCE_FAILED));
  }

  private ValidatedKnowledgeQuery boundedQuery() {
    return new ValidatedKnowledgeQuery(
        WORKSPACE_ID,
        tempDir,
        "First",
        Set.of(),
        false,
        false,
        new RetrievalBudgets(10, 2, 2, 1, 1, 1, 10, Duration.ofSeconds(1)));
  }

  private KnowledgeRetriever retriever(CandidateSource... sources) {
    return new HybridKnowledgeRetriever(
        new KnowledgeQueryValidator(Map.of(WORKSPACE_ID, tempDir)),
        List.of(sources),
        GitEvidenceSource.disabled());
  }

  private static CandidateSource fixedSource(RetrievalCandidate candidate) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return RetrievalSource.STRUCTURED_STORE;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        return new CandidateBatch(source(), List.of(candidate), List.of(), false);
      }
    };
  }

  private static CandidateSource throwingSource(RetrievalSource source) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return source;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        throw new IllegalStateException("fixture failure");
      }
    };
  }

  private static RetrievalCandidate candidate(String objectId) {
    return new RetrievalCandidate(
        Optional.of(objectId),
        ObjectType.DOCUMENT,
        objectId,
        Optional.of("fixture"),
        SourceRef.of("repository", "docs/available.md"),
        Optional.of(KnowledgeClass.CANONICAL),
        RetrievalFreshness.CURRENT_CANONICAL,
        RetrievalMatchCategory.STRUCTURED_OBJECT,
        RetrievalSource.STRUCTURED_STORE,
        0,
        true,
        List.of(),
        Optional.of("hash"),
        Optional.empty());
  }

  private static CodeSearchMatch codeMatch(String path) {
    Evidence evidence = sourceEvidence(path, "line:1");
    return new CodeSearchMatch(
        path, new CodeSearchRange(1, 1, 1, 6), "First", false, "First", evidence);
  }

  private static ExtractedSymbol symbol(String name) {
    String path = "src/" + name + ".java";
    return new ExtractedSymbol(
        new SymbolIdentity("symbol:" + name, true),
        SymbolKind.CLASS,
        SymbolLanguage.JAVA,
        name,
        Optional.of(name),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        path,
        new SymbolSourceRange(1, Optional.of(2)),
        new Evidence(
            EvidenceType.STATIC_ANALYSIS,
            SourceRef.of("engineering-workspace", WORKSPACE_ID + ":" + path),
            "line:1",
            1.0d));
  }

  private static GitEvidenceResult gitResult() {
    Evidence commitEvidence = versionEvidence("commit:abc");
    GitFileChange first =
        new GitFileChange(
            GitChangeKind.MODIFY,
            Optional.of("src/First.java"),
            Optional.of("src/First.java"),
            false,
            versionEvidence("commit:abc#src/First.java"));
    GitFileChange second =
        new GitFileChange(
            GitChangeKind.MODIFY,
            Optional.of("src/Second.java"),
            Optional.of("src/Second.java"),
            false,
            versionEvidence("commit:abc#src/Second.java"));
    GitCommitEvidence commit =
        new GitCommitEvidence(
            "abc",
            List.of(),
            "Author",
            "author@example.test",
            Instant.EPOCH,
            "Committer",
            "committer@example.test",
            Instant.EPOCH,
            "fixture",
            List.of(first, second),
            commitEvidence);
    return new GitEvidenceResult(
        List.of(commit),
        new GitToolProfile(
            "git-readonly-v1", Optional.of("git 2"), Optional.of("sha1"), Optional.of(false)),
        List.of(),
        true);
  }

  private static Evidence sourceEvidence(String path, String location) {
    return new Evidence(
        EvidenceType.SOURCE_TEXT,
        SourceRef.of("engineering-workspace", WORKSPACE_ID + ":" + path),
        location,
        1.0d);
  }

  private static Evidence versionEvidence(String location) {
    return new Evidence(
        EvidenceType.VERSION_CONTROL,
        SourceRef.of("engineering-workspace", WORKSPACE_ID + ":.git"),
        location,
        1.0d);
  }

  private static boolean hasDiagnostic(
      List<RetrievalDiagnostic> diagnostics, RetrievalDiagnosticCode code) {
    return diagnostics.stream().anyMatch(item -> item.code() == code);
  }

  private static List<String> objectIds(RetrievalResult result) {
    return result.items().stream().map(item -> item.objectId().orElseThrow()).toList();
  }
}
