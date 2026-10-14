package me.asu.kb.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import me.asu.kb.adapter.codesearch.CodeSearchMatch;
import me.asu.kb.adapter.codesearch.CodeSearchResult;
import me.asu.kb.adapter.codesearch.ConstrainedCodeSearcher;
import me.asu.kb.context.ContextBuilder;
import me.asu.kb.context.ContextIntent;
import me.asu.kb.context.ContextItem;
import me.asu.kb.context.ContextRequest;
import me.asu.kb.context.EngineeringContext;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.read.KnowledgeObjectReadRequest;
import me.asu.kb.read.KnowledgeObjectReadResult;
import me.asu.kb.read.KnowledgeObjectReader;
import me.asu.kb.read.KnowledgeObjectView;
import me.asu.kb.retrieval.KnowledgeQuery;
import me.asu.kb.retrieval.KnowledgeRetriever;
import me.asu.kb.retrieval.RetrievalItem;
import me.asu.kb.retrieval.RetrievalResult;
import me.asu.kb.trace.KnowledgeRelationTracer;
import me.asu.kb.trace.RelationTraceObject;
import me.asu.kb.trace.RelationTraceRequest;
import me.asu.kb.trace.RelationTraceResult;

/** Default, read-only mapping from stable agent values to application modules. */
public final class DefaultKnowledgeAgentFacade implements KnowledgeAgentFacade {
  private final KnowledgeRetriever retriever;
  private final KnowledgeObjectReader objectReader;
  private final ConstrainedCodeSearcher codeSearcher;
  private final KnowledgeRelationTracer relationTracer;
  private final ContextBuilder contextBuilder;
  private final AgentOutputSanitizer sanitizer;

  public DefaultKnowledgeAgentFacade(
      KnowledgeRetriever retriever,
      KnowledgeObjectReader objectReader,
      ConstrainedCodeSearcher codeSearcher,
      KnowledgeRelationTracer relationTracer,
      ContextBuilder contextBuilder,
      AgentOutputSanitizer sanitizer) {
    this.retriever = Objects.requireNonNull(retriever, "retriever");
    this.objectReader = Objects.requireNonNull(objectReader, "objectReader");
    this.codeSearcher = Objects.requireNonNull(codeSearcher, "codeSearcher");
    this.relationTracer = Objects.requireNonNull(relationTracer, "relationTracer");
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeSearchData> knowledgeSearch(
      AgentToolValues.KnowledgeSearchRequest request) {
    return safely(
        () -> {
          Set<ObjectType> types =
              request.objectTypes().stream().map(ObjectType::valueOf).collect(Collectors.toSet());
          RetrievalResult result =
              retriever.retrieve(
                  new KnowledgeQuery(
                      request.workspaceId(),
                      request.query(),
                      types,
                      request.resultLimit(),
                      request.includeStale(),
                      request.requireEvidence(),
                      request.virtualProjectId()));
          var data =
              new AgentToolValues.KnowledgeSearchData(
                  result.workspaceId(), mapItems(result.workspaceId(), result.items()));
          return response(
              result.complete() ? AgentToolValues.Status.OK : AgentToolValues.Status.INCOMPLETE,
              result.complete(),
              data,
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () -> new AgentToolValues.KnowledgeSearchData(request.workspaceId(), List.of()));
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.KnowledgeGetData> knowledgeGet(
      AgentToolValues.KnowledgeGetRequest request) {
    return safely(
        () -> {
          KnowledgeObjectReadResult result =
              objectReader.read(new KnowledgeObjectReadRequest(request.workspaceId(), request.objectId()));
          AgentToolValues.Status status =
              switch (result.status()) {
                case FOUND -> AgentToolValues.Status.OK;
                case NOT_FOUND -> AgentToolValues.Status.NOT_FOUND;
                case UNAVAILABLE -> AgentToolValues.Status.UNAVAILABLE;
              };
          AgentToolValues.KnowledgeItem object =
              result.object().map(v -> mapObject(request.workspaceId(), v)).orElse(null);
          return response(
              status,
              status != AgentToolValues.Status.UNAVAILABLE,
              new AgentToolValues.KnowledgeGetData(request.workspaceId(), object),
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () -> new AgentToolValues.KnowledgeGetData(request.workspaceId(), null));
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.CodeSymbolData> codeSymbol(
      AgentToolValues.CodeSymbolRequest request) {
    return safely(
        () -> {
          RetrievalResult result =
              retriever.retrieve(
                  new KnowledgeQuery(
                      request.workspaceId(),
                      request.query(),
                      Set.of(ObjectType.CODE_SYMBOL),
                      request.resultLimit(),
                      false,
                      false));
          return response(
              result.complete() ? AgentToolValues.Status.OK : AgentToolValues.Status.INCOMPLETE,
              result.complete(),
              new AgentToolValues.CodeSymbolData(
                  result.workspaceId(), mapItems(result.workspaceId(), result.items())),
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () -> new AgentToolValues.CodeSymbolData(request.workspaceId(), List.of()));
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.CodeSearchData> codeSearch(
      AgentToolValues.CodeSearchRequest request) {
    return safely(
        () -> {
          CodeSearchResult result =
              codeSearcher.search(
                  new me.asu.kb.adapter.codesearch.CodeSearchRequest(
                      request.workspaceId(),
                      request.literalQuery(),
                      request.relativeScopes(),
                      request.contextLines(),
                      request.maxResults()));
          List<AgentToolValues.CodeMatch> matches =
              result.matches().stream().map(m -> mapCodeMatch(request.workspaceId(), m)).toList();
          return response(
              result.complete() ? AgentToolValues.Status.OK : AgentToolValues.Status.INCOMPLETE,
              result.complete(),
              new AgentToolValues.CodeSearchData(request.workspaceId(), matches, !result.complete()),
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () -> new AgentToolValues.CodeSearchData(request.workspaceId(), List.of(), false));
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.TraceData> trace(
      AgentToolValues.TraceRequest request) {
    return safely(
        () -> {
          RelationTraceResult result =
              relationTracer.trace(new RelationTraceRequest(request.workspaceId(), request.startObjectId()));
          AgentToolValues.Status status =
              switch (result.status()) {
                case FOUND -> result.complete() ? AgentToolValues.Status.OK : AgentToolValues.Status.INCOMPLETE;
                case NOT_FOUND -> AgentToolValues.Status.NOT_FOUND;
                case UNAVAILABLE -> AgentToolValues.Status.UNAVAILABLE;
              };
          return response(
              status,
              result.complete(),
              new AgentToolValues.TraceData(
                  request.workspaceId(),
                  result.startObject().map(o -> mapTraceObject(request.workspaceId(), o)).orElse(null),
                  result.relations().stream()
                      .map(
                          r ->
                              new AgentToolValues.TracedRelation(
                                  r.relationId(),
                                  mapTraceObject(request.workspaceId(), r.source()),
                                  r.relationType().name(),
                                  mapTraceObject(request.workspaceId(), r.target()),
                                  mapEvidence(request.workspaceId(), r.evidence())))
                      .toList()),
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () -> new AgentToolValues.TraceData(request.workspaceId(), null, List.of()));
  }

  @Override
  public AgentToolValues.ToolResponse<AgentToolValues.BuildContextData> buildContext(
      AgentToolValues.BuildContextRequest request) {
    return safely(
        () -> {
          EngineeringContext result =
              contextBuilder.build(
                  new ContextRequest(
                      request.workspaceId(),
                      request.target(),
                      ContextIntent.valueOf(request.intent()),
                      request.tokenBudget(),
                      request.includeStale(),
                      request.requireEvidence()));
          var data =
              new AgentToolValues.BuildContextData(
                  result.workspaceId(),
                  result.target(),
                  result.intent().map(Enum::name).orElse(request.intent()),
                  result.items().stream().map(i -> mapContextItem(result.workspaceId(), i)).toList(),
                  result.estimatedTokens(),
                  result.estimatorVersion(),
                  result.omissions().stream()
                      .map(
                          o ->
                              new AgentToolValues.Omission(
                                  o.objectId().orElse(null),
                                  o.reason().name(),
                                  sanitizer.diagnostic("OMISSION", o.detail()).detail()))
                      .toList());
          return response(
              result.complete() ? AgentToolValues.Status.OK : AgentToolValues.Status.INCOMPLETE,
              result.complete(),
              data,
              result.diagnostics().stream()
                  .map(d -> sanitizer.diagnostic(d.code().name(), d.detail()))
                  .toList());
        },
        () ->
            new AgentToolValues.BuildContextData(
                request.workspaceId(), request.target(), request.intent(), List.of(), 0, "unknown", List.of()));
  }

  private List<AgentToolValues.KnowledgeItem> mapItems(
      String workspaceId, List<RetrievalItem> items) {
    return items.stream()
        .map(
            item ->
                new AgentToolValues.KnowledgeItem(
                    item.objectId().orElse(null),
                    item.objectType().name(),
                    item.title(),
                    item.excerpt().orElse(null),
                    mapSource(
                        workspaceId,
                        item.source(),
                        item.virtualProjectId().orElse(null),
                        null,
                        null),
                    item.virtualProjectId().isPresent() ? "VIRTUAL_PROJECT" : "SHARED",
                    item.virtualProjectId().orElse(null),
                    item.freshness().name(),
                    item.contributingSources().stream()
                        .map(Enum::name)
                        .sorted(Comparator.naturalOrder())
                        .toList(),
                    mapEvidence(
                        workspaceId,
                        item.evidence(),
                        item.virtualProjectId().orElse(null))))
        .toList();
  }

  private AgentToolValues.KnowledgeItem mapObject(String workspaceId, KnowledgeObjectView view) {
    KnowledgeObject object = view.object();
    List<String> provenance = new ArrayList<>();
    provenance.add(object.knowledgeClass().name());
    object.canonicalSourceId().ifPresent(provenance::add);
    object.generatorId().ifPresent(provenance::add);
    object.generatorVersion().ifPresent(provenance::add);
    return new AgentToolValues.KnowledgeItem(
        object.id(),
        object.type().name(),
        object.title(),
        object.content().orElse(null),
        mapSource(workspaceId, object.source(), null, null),
        view.freshness().name(),
        provenance,
        mapEvidence(workspaceId, view.evidence()));
  }

  private AgentToolValues.CodeMatch mapCodeMatch(String workspaceId, CodeSearchMatch match) {
    return new AgentToolValues.CodeMatch(
        relativePath(workspaceId, match.relativePath()),
        match.range().startLine(),
        match.range().endLine(),
        match.excerpt(),
        match.excerptTruncated(),
        match.matchedText(),
        mapEvidence(workspaceId, List.of(match.evidence())).get(0));
  }

  private AgentToolValues.TraceObject mapTraceObject(
      String workspaceId, RelationTraceObject object) {
    return new AgentToolValues.TraceObject(
        object.objectId(),
        object.objectType().name(),
        object.objectId(),
        mapSource(workspaceId, object.source(), null, null),
        object.freshness().name());
  }

  private AgentToolValues.ContextItem mapContextItem(String workspaceId, ContextItem item) {
    Integer start = item.sourceRange().map(r -> r.startLine()).orElse(null);
    Integer end = item.sourceRange().map(r -> r.endLine()).orElse(null);
    return new AgentToolValues.ContextItem(
        item.objectId().orElse(null),
        item.objectType().name(),
        item.title(),
        item.fragment(),
        mapSource(workspaceId, item.source(), start, end),
        item.freshness().name(),
        item.provenance().contributingSources().stream().map(Enum::name).sorted().toList(),
        mapEvidence(workspaceId, item.provenance().evidence()),
        item.selectionReason().name(),
        item.truncated(),
        item.estimatedTokens());
  }

  private AgentToolValues.Source mapSource(
      String workspaceId, SourceRef source, Integer startLine, Integer endLine) {
    return mapSource(workspaceId, source, null, startLine, endLine);
  }

  private AgentToolValues.Source mapSource(
      String workspaceId,
      SourceRef source,
      String virtualProjectId,
      Integer startLine,
      Integer endLine) {
    return new AgentToolValues.Source(
        source.system(),
        virtualProjectId == null
            ? relativePath(workspaceId, source.locator())
            : virtualProjectPath(virtualProjectId, source.locator()),
        startLine,
        endLine);
  }

  private List<AgentToolValues.Evidence> mapEvidence(
      String workspaceId, List<Evidence> evidence) {
    return mapEvidence(workspaceId, evidence, null);
  }

  private List<AgentToolValues.Evidence> mapEvidence(
      String workspaceId, List<Evidence> evidence, String virtualProjectId) {
    return evidence.stream()
        .map(
            item ->
                new AgentToolValues.Evidence(
                    item.type().name(),
                    item.source().system(),
                    virtualProjectId == null
                        ? relativePath(workspaceId, item.source().locator())
                        : virtualProjectPath(virtualProjectId, item.source().locator()),
                    null,
                    null,
                    item.location()))
        .toList();
  }

  private static String virtualProjectPath(String projectId, String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw new FacadeMappingException(
          new AgentRequestException(
              AgentToolValues.Status.INTERNAL, "UNSAFE_OUTPUT", "output path is unavailable"));
    }
    String normalized = rawPath.replace('\\', '/');
    try {
      Path path = Path.of(rawPath);
      if (path.isAbsolute() || normalized.startsWith("/") || normalized.matches("(?i)^[a-z]:/.*")) {
        while (normalized.startsWith("/")) {
          normalized = normalized.substring(1);
        }
        normalized = "virtual-projects/" + projectId + "/" + normalized;
      }
      Path safe = Path.of(normalized);
      for (Path segment : safe) {
        if ("..".equals(segment.toString())) {
          throw new InvalidPathException(normalized, "parent path segment");
        }
      }
      return normalized;
    } catch (InvalidPathException failure) {
      throw new FacadeMappingException(
          new AgentRequestException(
              AgentToolValues.Status.INTERNAL, "UNSAFE_OUTPUT", "output path is unavailable"));
    }
  }

  private <T> AgentToolValues.ToolResponse<T> safely(
      Supplier<AgentToolValues.ToolResponse<T>> operation, Supplier<T> emptyData) {
    try {
      return operation.get();
    } catch (FacadeMappingException mappingFailure) {
      AgentRequestException failure = mappingFailure.failure;
      return response(
          failure.status(),
          false,
          emptyData.get(),
          List.of(sanitizer.diagnostic(failure.code(), failure.getMessage())));
    } catch (RuntimeException failure) {
      return response(
          AgentToolValues.Status.INTERNAL,
          false,
          emptyData.get(),
          List.of(sanitizer.diagnostic("INTERNAL", "operation did not complete")));
    }
  }

  private String relativePath(String workspaceId, String rawPath) {
    try {
      return sanitizer.relativePath(workspaceId, rawPath);
    } catch (AgentRequestException failure) {
      throw new FacadeMappingException(failure);
    }
  }

  private static final class FacadeMappingException extends RuntimeException {
    private final AgentRequestException failure;

    private FacadeMappingException(AgentRequestException failure) {
      super(failure);
      this.failure = failure;
    }
  }

  private static <T> AgentToolValues.ToolResponse<T> response(
      AgentToolValues.Status status,
      boolean complete,
      T data,
      List<AgentToolValues.Diagnostic> diagnostics) {
    return new AgentToolValues.ToolResponse<>(
        AgentToolValues.SCHEMA_VERSION, status, complete, data, diagnostics);
  }
}
