package me.asu.kb.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.adapter.codesearch.CodeSearchRequest;
import me.asu.kb.adapter.codesearch.ConstrainedCodeSearcher;
import me.asu.kb.adapter.symbol.CtagsSymbolExtractor;
import me.asu.kb.adapter.symbol.SymbolExtractionRequest;
import me.asu.kb.adapter.symbol.VerifiedSymbolExtraction;
import me.asu.kb.store.SqliteKnowledgeStore;

/** Public construction boundary that keeps retrieval implementation types package-private. */
public final class RetrievalRuntimeFactory {
  private static final int MAX_JAVA_FILES = 64;

  private RetrievalRuntimeFactory() {}

  public static RetrievalRuntime open(
      String workspaceId,
      Map<String, Path> workspaceRoots,
      SqliteKnowledgeStore store,
      ConstrainedCodeSearcher codeSearcher,
      CtagsSymbolExtractor symbolExtractor,
      Path indexPath)
      throws IOException, SQLException {
    return open(
        workspaceId,
        workspaceRoots,
        store,
        codeSearcher,
        symbolExtractor,
        indexPath,
        null);
  }

  public static RetrievalRuntime open(
      String workspaceId,
      Map<String, Path> workspaceRoots,
      SqliteKnowledgeStore store,
      ConstrainedCodeSearcher codeSearcher,
      CtagsSymbolExtractor symbolExtractor,
      Path indexPath,
      VirtualProjectIndexManager virtualProjectIndexes)
      throws IOException, SQLException {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(codeSearcher, "codeSearcher");
    Objects.requireNonNull(symbolExtractor, "symbolExtractor");
    EngineeringKnowledgeLuceneProjection projection =
        new EngineeringKnowledgeLuceneProjection(indexPath);
    boolean completed = false;
    try {
      projection.rebuildFromStore(workspaceId, store);
      AdapterCandidateNormalizer normalizer = new AdapterCandidateNormalizer();
      List<CandidateSource> sources =
          new ArrayList<>(
              List.of(
                  new StructuralStoreCandidateSource(workspaceId, store),
                  new LuceneBm25LexicalRetriever(projection, store),
                  ripgrepSource(codeSearcher, normalizer),
                  ctagsSource(symbolExtractor, normalizer)));
      if (virtualProjectIndexes != null) {
        sources.add(0, new VirtualProjectLuceneCandidateSource(store, virtualProjectIndexes));
      }
      KnowledgeRetriever retriever =
          new HybridKnowledgeRetriever(
              new KnowledgeQueryValidator(workspaceRoots), sources, GitEvidenceSource.disabled());
      DefaultRetrievalRuntime runtime =
          new DefaultRetrievalRuntime(workspaceId, store, projection, retriever);
      completed = true;
      return runtime;
    } finally {
      if (!completed) {
        projection.close();
      }
    }
  }

  private static CandidateSource ripgrepSource(
      ConstrainedCodeSearcher searcher, AdapterCandidateNormalizer normalizer) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return RetrievalSource.RIPGREP_TEXT;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        return normalizer.normalizeRipgrep(
            searcher.search(
                new CodeSearchRequest(
                    query.workspaceId(),
                    query.text(),
                    List.of(),
                    0,
                    query.budgets().ripgrepCandidates())),
            query);
      }
    };
  }

  private static CandidateSource ctagsSource(
      CtagsSymbolExtractor extractor, AdapterCandidateNormalizer normalizer) {
    return new CandidateSource() {
      @Override
      public RetrievalSource source() {
        return RetrievalSource.CTAGS_SYMBOL;
      }

      @Override
      public CandidateBatch collect(ValidatedKnowledgeQuery query) {
        List<String> files;
        try {
          files = javaFiles(query.workspaceRoot());
        } catch (IOException error) {
          return unavailable(RetrievalSource.CTAGS_SYMBOL, "Java source discovery failed");
        }
        if (files.isEmpty()) {
          return new CandidateBatch(RetrievalSource.CTAGS_SYMBOL, List.of(), List.of(), false);
        }
        var result =
            extractor.extract(
                new SymbolExtractionRequest(
                    query.workspaceId(), "Java", files, query.budgets().ctagsCandidates()));
        return normalizer.normalizeCtags(new VerifiedSymbolExtraction(result, Map.of()), query);
      }
    };
  }

  private static List<String> javaFiles(Path root) throws IOException {
    List<String> result = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .limit(MAX_JAVA_FILES)
          .map(root::relativize)
          .map(path -> path.toString().replace('\\', '/'))
          .forEach(result::add);
    }
    return List.copyOf(result);
  }

  private static CandidateBatch unavailable(RetrievalSource source, String detail) {
    return new CandidateBatch(
        source,
        List.of(),
        List.of(
            new RetrievalDiagnostic(
                Optional.of(source), RetrievalDiagnosticCode.SOURCE_UNAVAILABLE, detail)),
        false);
  }

  private static final class DefaultRetrievalRuntime implements RetrievalRuntime {
    private final String workspaceId;
    private final SqliteKnowledgeStore store;
    private final EngineeringKnowledgeLuceneProjection projection;
    private final KnowledgeRetriever retriever;

    private DefaultRetrievalRuntime(
        String workspaceId,
        SqliteKnowledgeStore store,
        EngineeringKnowledgeLuceneProjection projection,
        KnowledgeRetriever retriever) {
      this.workspaceId = workspaceId;
      this.store = store;
      this.projection = projection;
      this.retriever = retriever;
    }

    @Override
    public KnowledgeRetriever retriever() {
      return retriever;
    }

    @Override
    public void rebuild() throws IOException, SQLException {
      projection.rebuildFromStore(workspaceId, store);
    }

    @Override
    public void close() throws IOException {
      projection.close();
    }
  }
}
