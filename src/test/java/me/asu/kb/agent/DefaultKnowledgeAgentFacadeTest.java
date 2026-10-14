package me.asu.kb.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import me.asu.kb.adapter.codesearch.CodeSearchDiagnostic;
import me.asu.kb.adapter.codesearch.CodeSearchDiagnosticCode;
import me.asu.kb.adapter.codesearch.CodeSearchMatch;
import me.asu.kb.adapter.codesearch.CodeSearchRange;
import me.asu.kb.adapter.codesearch.CodeSearchResult;
import me.asu.kb.context.ContextDiagnostic;
import me.asu.kb.context.ContextDiagnosticCode;
import me.asu.kb.context.ContextIntent;
import me.asu.kb.context.EngineeringContext;
import me.asu.kb.context.OmissionReason;
import me.asu.kb.context.OmissionRecord;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.RelationType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.read.KnowledgeObjectReadResult;
import me.asu.kb.retrieval.KnowledgeQuery;
import me.asu.kb.retrieval.RankExplanation;
import me.asu.kb.retrieval.RetrievalDiagnostic;
import me.asu.kb.retrieval.RetrievalDiagnosticCode;
import me.asu.kb.retrieval.RetrievalFreshness;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalMatchCategory;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.retrieval.RetrievalSource;
import me.asu.kb.trace.RelationTraceDiagnostic;
import me.asu.kb.trace.RelationTraceObject;
import me.asu.kb.trace.RelationTraceResult;
import me.asu.kb.trace.TracedRelation;
import me.asu.kb.workspace.EngineeringWorkspace;
import me.asu.kb.workspace.ImmutableEngineeringWorkspaceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultKnowledgeAgentFacadeTest {
  @TempDir Path root;

  @Test
  void delegatesAllSixOperationsThroughApplicationSeams() {
    List<KnowledgeQuery> retrievalQueries = new ArrayList<>();
    AtomicReference<me.asu.kb.adapter.codesearch.CodeSearchRequest> codeRequest =
        new AtomicReference<>();
    AtomicReference<me.asu.kb.context.ContextRequest> contextRequest = new AtomicReference<>();
    DefaultKnowledgeAgentFacade facade =
        facade(
            query -> {
              retrievalQueries.add(query);
              return new RetrievalResult(query.workspaceId(), List.of(), List.of(), true);
            },
            request -> KnowledgeObjectReadResult.notFound(List.of()),
            request -> {
              codeRequest.set(request);
              return new CodeSearchResult(List.of(), List.of(), true);
            },
            request -> RelationTraceResult.notFound(List.of()),
            request -> {
              contextRequest.set(request);
              return new EngineeringContext(
                  request.workspaceId(),
                  request.target(),
                  Optional.of(request.intent()),
                  List.of(),
                  0,
                  "utf8-bytes-v1",
                  true,
                  List.of(),
                  List.of());
            });

    assertEquals(
        AgentToolValues.Status.OK,
        facade
            .knowledgeSearch(
                new AgentToolValues.KnowledgeSearchRequest(
                    "repo", "cache", Set.of("DOCUMENT"), 9, true, true))
            .status());
    assertEquals(
        AgentToolValues.Status.NOT_FOUND,
        facade.knowledgeGet(new AgentToolValues.KnowledgeGetRequest("repo", "doc:1")).status());
    assertEquals(
        AgentToolValues.Status.OK,
        facade.codeSymbol(new AgentToolValues.CodeSymbolRequest("repo", "Parser", 7)).status());
    assertEquals(
        AgentToolValues.Status.OK,
        facade
            .codeSearch(
                new AgentToolValues.CodeSearchRequest(
                    "repo", "needle", List.of("src/main"), 2, 12))
            .status());
    assertEquals(
        AgentToolValues.Status.NOT_FOUND,
        facade.trace(new AgentToolValues.TraceRequest("repo", "symbol:1")).status());
    assertEquals(
        AgentToolValues.Status.OK,
        facade
            .buildContext(
                new AgentToolValues.BuildContextRequest(
                    "repo", "Parser", "REVIEW", 512, false, true))
            .status());

    assertEquals(Set.of(ObjectType.DOCUMENT), retrievalQueries.get(0).objectTypes());
    assertEquals(Set.of(ObjectType.CODE_SYMBOL), retrievalQueries.get(1).objectTypes());
    assertEquals("needle", codeRequest.get().literalQuery());
    assertEquals(ContextIntent.REVIEW, contextRequest.get().intent());
  }

  @Test
  void preservesIncompleteDiagnosticsAndSanitizesUnexpectedFailures() {
    RetrievalDiagnostic partialDiagnostic =
        new RetrievalDiagnostic(
            Optional.empty(), RetrievalDiagnosticCode.SOURCE_UNAVAILABLE, "source unavailable");
    DefaultKnowledgeAgentFacade partialFacade =
        facade(
            query ->
                new RetrievalResult(query.workspaceId(), List.of(), List.of(partialDiagnostic), false),
            request -> KnowledgeObjectReadResult.notFound(List.of()),
            request -> new CodeSearchResult(List.of(), List.of(), true),
            request -> RelationTraceResult.notFound(List.of()),
            request -> {
              throw new IllegalStateException("SELECT secret FROM internal_table");
            });

    var partial =
        partialFacade.knowledgeSearch(
            new AgentToolValues.KnowledgeSearchRequest(
                "repo", "cache", Set.of(), 10, false, false));
    var internal =
        partialFacade.buildContext(
            new AgentToolValues.BuildContextRequest(
                "repo", "target", "UNDERSTAND", 512, false, false));

    assertEquals(AgentToolValues.Status.INCOMPLETE, partial.status());
    assertFalse(partial.complete());
    assertEquals("SOURCE_UNAVAILABLE", partial.diagnostics().get(0).code());
    assertEquals(AgentToolValues.Status.INTERNAL, internal.status());
    assertFalse(internal.complete());
    assertEquals("operation did not complete", internal.diagnostics().get(0).detail());
    assertTrue(internal.data().items().isEmpty());
  }

  @Test
  void mapsTraceableResultsAndPartialSemanticsForAllSixTools() {
    SourceRef source = SourceRef.of("repository", "src/Parser.java");
    Evidence evidence = new Evidence(EvidenceType.SOURCE_TEXT, source, "line 7", 0.95d);
    RetrievalItem retrievalItem =
        new RetrievalItem(
            Optional.of("symbol:Parser"),
            ObjectType.CODE_SYMBOL,
            "Parser",
            Optional.of("class Parser"),
            source,
            Optional.empty(),
            RetrievalFreshness.CURRENT_DERIVED,
            Set.of(RetrievalSource.CTAGS_SYMBOL, RetrievalSource.STRUCTURED_STORE),
            List.of(evidence),
            new RankExplanation(
                RetrievalFreshness.CURRENT_DERIVED,
                RetrievalMatchCategory.EXACT_SYMBOL,
                true,
                0,
                "symbol:Parser",
                "exact symbol"));
    CodeSearchMatch codeMatch =
        new CodeSearchMatch(
            "src/Parser.java",
            new CodeSearchRange(7, 1, 7, 13),
            "class Parser",
            true,
            "Parser",
            evidence);
    RelationTraceObject endpoint =
        new RelationTraceObject(
            "symbol:Parser", ObjectType.CODE_SYMBOL, source, RetrievalFreshness.CURRENT_DERIVED);
    TracedRelation relation =
        new TracedRelation(
            "relation:1", endpoint, RelationType.IMPLEMENTS, endpoint, List.of(evidence));
    OmissionRecord omission =
        new OmissionRecord(
            OmissionReason.BUDGET,
            Optional.of("symbol:Other"),
            Optional.of(source),
            "token budget reached");
    ContextDiagnostic contextDiagnostic =
        new ContextDiagnostic(
            ContextDiagnosticCode.RETRIEVAL_INCOMPLETE,
            "token budget reached",
            Optional.of("symbol:Other"),
            Optional.empty());

    DefaultKnowledgeAgentFacade facade =
        facade(
            query ->
                new RetrievalResult(
                    query.workspaceId(),
                    List.of(retrievalItem),
                    List.of(
                        new RetrievalDiagnostic(
                            Optional.of(RetrievalSource.CTAGS_SYMBOL),
                            RetrievalDiagnosticCode.SOURCE_UNAVAILABLE,
                            "symbol source unavailable")),
                    false),
            request -> KnowledgeObjectReadResult.notFound(List.of()),
            request ->
                new CodeSearchResult(
                    List.of(codeMatch),
                    List.of(
                        new CodeSearchDiagnostic(
                            CodeSearchDiagnosticCode.RESULT_TRUNCATED,
                            "result limit reached")),
                    false),
            request ->
                RelationTraceResult.found(
                    endpoint,
                    List.of(relation),
                    false,
                    List.of(
                        new RelationTraceDiagnostic(
                            RelationTraceDiagnostic.Code.RESULT_LIMIT_REACHED,
                            "relation limit reached"))),
            request ->
                new EngineeringContext(
                    request.workspaceId(),
                    request.target(),
                    Optional.of(request.intent()),
                    List.of(),
                    0,
                    "utf8-bytes-v1",
                    false,
                    List.of(contextDiagnostic),
                    List.of(omission)));

    var search =
        facade.knowledgeSearch(
            new AgentToolValues.KnowledgeSearchRequest(
                "repo", "Parser", Set.of(), 10, false, false));
    var get = facade.knowledgeGet(new AgentToolValues.KnowledgeGetRequest("repo", "missing"));
    var symbol = facade.codeSymbol(new AgentToolValues.CodeSymbolRequest("repo", "Parser", 10));
    var code =
        facade.codeSearch(
            new AgentToolValues.CodeSearchRequest("repo", "Parser", List.of(), 1, 10));
    var trace = facade.trace(new AgentToolValues.TraceRequest("repo", "symbol:Parser"));
    var context =
        facade.buildContext(
            new AgentToolValues.BuildContextRequest(
                "repo", "Parser", "REVIEW", 512, false, true));

    assertEquals(AgentToolValues.Status.INCOMPLETE, search.status());
    assertEquals("CURRENT_DERIVED", search.data().items().get(0).freshness());
    assertEquals(
        List.of("CTAGS_SYMBOL", "STRUCTURED_STORE"),
        search.data().items().get(0).provenance());
    assertEquals(AgentToolValues.Status.NOT_FOUND, get.status());
    assertEquals("SOURCE_TEXT", symbol.data().symbols().get(0).evidence().get(0).type());
    assertEquals(AgentToolValues.Status.INCOMPLETE, code.status());
    assertTrue(code.data().truncated());
    assertTrue(code.data().matches().get(0).excerptTruncated());
    assertEquals(AgentToolValues.Status.INCOMPLETE, trace.status());
    assertEquals("IMPLEMENTS", trace.data().relations().get(0).relationType());
    assertFalse(trace.data().relations().get(0).evidence().isEmpty());
    assertEquals(AgentToolValues.Status.INCOMPLETE, context.status());
    assertEquals("utf8-bytes-v1", context.data().estimatorVersion());
    assertEquals("BUDGET", context.data().omissions().get(0).reason());
    assertEquals("symbol:Other", context.data().omissions().get(0).objectId());
  }

  private DefaultKnowledgeAgentFacade facade(
      me.asu.kb.retrieval.KnowledgeRetriever retriever,
      me.asu.kb.read.KnowledgeObjectReader reader,
      me.asu.kb.adapter.codesearch.ConstrainedCodeSearcher codeSearcher,
      me.asu.kb.trace.KnowledgeRelationTracer tracer,
      me.asu.kb.context.ContextBuilder contextBuilder) {
    var catalog =
        new ImmutableEngineeringWorkspaceCatalog(List.of(new EngineeringWorkspace("repo", root)));
    return new DefaultKnowledgeAgentFacade(
        retriever,
        reader,
        codeSearcher,
        tracer,
        contextBuilder,
        new AgentOutputSanitizer(catalog));
  }
}
