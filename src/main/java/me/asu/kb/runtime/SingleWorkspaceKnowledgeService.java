package me.asu.kb.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import me.asu.kb.adapter.codesearch.RipgrepCodeSearchAdapter;
import me.asu.kb.adapter.gitevidence.GitEvidenceSettings;
import me.asu.kb.adapter.gitevidence.LocalGitKnowledgeAdapter;
import me.asu.kb.adapter.gitevidence.GitRepositoryLocator;
import me.asu.kb.adapter.symbol.CtagsSymbolExtractor;
import me.asu.kb.agent.AgentOutputSanitizer;
import me.asu.kb.agent.DefaultKnowledgeAgentFacade;
import me.asu.kb.agent.KnowledgeAgentFacade;
import me.asu.kb.context.ContextBuilder;
import me.asu.kb.context.ContextBuilderFactory;
import me.asu.kb.ingestion.KnowledgeProjectionIngestion;
import me.asu.kb.ingestion.SqliteKnowledgeProjectionIngestion;
import me.asu.kb.read.KnowledgeObjectReader;
import me.asu.kb.read.SqliteKnowledgeObjectReader;
import me.asu.kb.retrieval.KnowledgeRetriever;
import me.asu.kb.retrieval.RetrievalRuntime;
import me.asu.kb.retrieval.RetrievalRuntimeFactory;
import me.asu.kb.retrieval.VirtualProjectIndexManager;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.service.VirtualProjectPathPolicy;
import me.asu.kb.trace.KnowledgeRelationTracer;
import me.asu.kb.trace.SqliteKnowledgeRelationTracer;

/** Sole owner of the production resources for one new-format workspace. */
public final class SingleWorkspaceKnowledgeService
    implements SingleWorkspaceApplication, AutoCloseable {
  private final SingleWorkspaceDocumentApplication documentModule;
  private final VirtualProjectApplication virtualProjects;
  private final VirtualProjectIndexManager virtualProjectIndexes;
  private final VirtualProjectSyncScheduler virtualProjectSyncScheduler;
  private final SqliteKnowledgeStore store;
  private final RetrievalRuntime retrievalRuntime;
  private final KnowledgeProjectionIngestion projectionIngestion;
  private final KnowledgeObjectReader objectReader;
  private final KnowledgeRelationTracer relationTracer;
  private final ContextBuilder contextBuilder;
  private final KnowledgeAgentFacade agentFacade;
  private final ServiceOperationCoordinator operationCoordinator;
  @SuppressWarnings("unused")
  private final LocalGitKnowledgeAdapter gitAdapter;
  private final AtomicReference<ServiceLifecycleState> lifecycle =
      new AtomicReference<>(ServiceLifecycleState.STARTING);

  private SingleWorkspaceKnowledgeService(
      SingleWorkspaceDocumentApplication documentModule,
      VirtualProjectApplication virtualProjects,
      VirtualProjectIndexManager virtualProjectIndexes,
      VirtualProjectSyncScheduler virtualProjectSyncScheduler,
      SqliteKnowledgeStore store,
      RetrievalRuntime retrievalRuntime,
      KnowledgeProjectionIngestion projectionIngestion,
      KnowledgeObjectReader objectReader,
      KnowledgeRelationTracer relationTracer,
      ContextBuilder contextBuilder,
      KnowledgeAgentFacade agentFacade,
      LocalGitKnowledgeAdapter gitAdapter,
      ServiceOperationCoordinator operationCoordinator) {
    this.documentModule = documentModule;
    this.virtualProjects = virtualProjects;
    this.virtualProjectIndexes = virtualProjectIndexes;
    this.virtualProjectSyncScheduler = virtualProjectSyncScheduler;
    this.store = store;
    this.retrievalRuntime = retrievalRuntime;
    this.projectionIngestion = projectionIngestion;
    this.objectReader = objectReader;
    this.relationTracer = relationTracer;
    this.contextBuilder = contextBuilder;
    this.agentFacade = agentFacade;
    this.gitAdapter = gitAdapter;
    this.operationCoordinator = operationCoordinator;
    lifecycle.set(ServiceLifecycleState.READY);
  }

  public static SingleWorkspaceKnowledgeService open(
      SingleWorkspaceServiceConfig config,
      DocumentModuleFactory documentModuleFactory,
      ToolProfiles tools)
      throws Exception {
    return open(config, documentModuleFactory, tools, null, VirtualProjectSyncScheduler.DEFAULT_MAX_CONCURRENT_PROJECTS);
  }

  public static SingleWorkspaceKnowledgeService open(
      SingleWorkspaceServiceConfig config,
      DocumentModuleFactory documentModuleFactory,
      ToolProfiles tools,
      java.time.Duration virtualProjectSyncInterval,
      int virtualProjectSyncConcurrency)
      throws Exception {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(documentModuleFactory, "documentModuleFactory");
    Objects.requireNonNull(tools, "tools");
    SingleWorkspaceLayout.open(config);

    Path documentPath = config.resolveInsideWorkspace(Path.of("document"));
    Path statePath = config.resolveInsideWorkspace(Path.of("state"));
    Path indexPath = config.resolveInsideWorkspace(Path.of("index", "engineering"));
    Path virtualProjectIndexPath =
        config.resolveInsideWorkspace(Path.of("index", "virtual-projects"));
    Files.createDirectories(documentPath);
    Files.createDirectories(statePath);
    Files.createDirectories(indexPath);
    Files.createDirectories(virtualProjectIndexPath);

    SingleWorkspaceDocumentApplication documentModule = null;
    SqliteKnowledgeStore store = null;
    VirtualProjectIndexManager virtualProjectIndexes = null;
    VirtualProjectSyncScheduler virtualProjectSyncScheduler = null;
    RetrievalRuntime retrieval = null;
    ServiceOperationCoordinator operations = null;
    try {
      documentModule =
          Objects.requireNonNull(
              documentModuleFactory.open(documentPath), "documentModuleFactory result");
      store = new SqliteKnowledgeStore(statePath.resolve("engineering.sqlite"));
      virtualProjectIndexes = new VirtualProjectIndexManager(virtualProjectIndexPath);
      var projectPathPolicy = new VirtualProjectPathPolicy(config.workspaceRoot());
      var virtualProjects =
          new SqliteVirtualProjectApplication(
              store,
              projectPathPolicy,
              new GitRepositoryLocator(tools.git()),
              new VirtualProjectSynchronizer(store, projectPathPolicy, virtualProjectIndexes));
      var codeSearcher = new RipgrepCodeSearchAdapter(config.workspaceRoots(), tools.ripgrep());
      var symbolExtractor = new CtagsSymbolExtractor(config.workspaceRoots(), tools.ctags());
      var gitAdapter = new LocalGitKnowledgeAdapter(config.workspaceRoots(), tools.git());
      retrieval =
          RetrievalRuntimeFactory.open(
              SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID,
              config.workspaceRoots(),
              store,
              codeSearcher,
              symbolExtractor,
              indexPath,
              virtualProjectIndexes);
      var ingestion = new SqliteKnowledgeProjectionIngestion(store, config.workspaceRoots());
      var reader =
          new SqliteKnowledgeObjectReader(
              SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID, store);
      var tracer =
          new SqliteKnowledgeRelationTracer(
              SingleWorkspaceServiceConfig.INTERNAL_WORKSPACE_ID, store);
      var context = ContextBuilderFactory.create(retrieval.retriever(), tracer, reader);
      var facade =
          new DefaultKnowledgeAgentFacade(
              retrieval.retriever(),
              reader,
              codeSearcher,
              tracer,
              context,
              new AgentOutputSanitizer(config.workspaceCatalog()));
      operations = new ServiceOperationCoordinator();
      if (virtualProjectSyncInterval != null) {
        virtualProjectSyncScheduler =
            new VirtualProjectSyncScheduler(
                virtualProjects, virtualProjectSyncInterval, virtualProjectSyncConcurrency);
      }
      return new SingleWorkspaceKnowledgeService(
          documentModule,
          virtualProjects,
          virtualProjectIndexes,
          virtualProjectSyncScheduler,
          store,
          retrieval,
          ingestion,
          reader,
          tracer,
          context,
          facade,
          gitAdapter,
          operations);
    } catch (Exception failure) {
      closeAfterFailure(
          operations,
          virtualProjectSyncScheduler,
          virtualProjectIndexes,
          retrieval,
          store,
          documentModule,
          failure);
      throw failure;
    }
  }

  @Override
  public ServiceOperationCoordinator operationCoordinator() {
    return operationCoordinator;
  }

  @Override
  public SingleWorkspaceDocumentApplication documents() {
    return documentModule;
  }

  @Override
  public VirtualProjectApplication virtualProjects() {
    return virtualProjects;
  }

  @Override
  public VirtualProjectIndexManager virtualProjectIndexes() {
    return virtualProjectIndexes;
  }

  public ServiceReadiness readiness() {
    ServiceLifecycleState state = lifecycle.get();
    ServiceLifecycleState componentState = componentState(state);
    return new ServiceReadiness(
        ServiceReadiness.SCHEMA_VERSION,
        state == ServiceLifecycleState.READY && operationCoordinator.isAccepting(),
        state,
        null,
        Map.of(
            "store", componentState,
            "index", componentState,
            "application", componentState),
        operationCoordinator.queueDepth(),
        new ServiceReadiness.Freshness(null, null, null));
  }

  @Override
  public KnowledgeProjectionIngestion projectionIngestion() {
    return projectionIngestion;
  }

  @Override
  public KnowledgeRetriever retriever() {
    return retrievalRuntime.retriever();
  }

  @Override
  public KnowledgeObjectReader objectReader() {
    return objectReader;
  }

  @Override
  public KnowledgeRelationTracer relationTracer() {
    return relationTracer;
  }

  @Override
  public ContextBuilder contextBuilder() {
    return contextBuilder;
  }

  @Override
  public KnowledgeAgentFacade agentFacade() {
    return agentFacade;
  }

  @Override
  public void close() throws Exception {
    if (!lifecycle.compareAndSet(ServiceLifecycleState.READY, ServiceLifecycleState.CLOSING)) {
      return;
    }
    operationCoordinator.stopAccepting();
    Exception failure = closeOne(virtualProjectSyncScheduler, null);
    failure = closeOne(operationCoordinator, failure);
    failure = closeOne(documentModule, failure);
    failure = closeOne(virtualProjectIndexes, failure);
    failure = closeOne(retrievalRuntime, failure);
    failure = closeOne(store, failure);
    lifecycle.set(ServiceLifecycleState.CLOSED);
    if (failure != null) {
      throw failure;
    }
  }

  private static ServiceLifecycleState componentState(ServiceLifecycleState state) {
    return switch (state) {
      case READY -> ServiceLifecycleState.READY;
      case CLOSING, CLOSED -> ServiceLifecycleState.CLOSING;
      case DEGRADED -> ServiceLifecycleState.DEGRADED;
      case FAILED -> ServiceLifecycleState.FAILED;
      case STARTING -> ServiceLifecycleState.STARTING;
    };
  }

  private static void closeAfterFailure(
      ServiceOperationCoordinator operations,
      VirtualProjectSyncScheduler virtualProjectSyncScheduler,
      VirtualProjectIndexManager virtualProjectIndexes,
      RetrievalRuntime retrieval,
      SqliteKnowledgeStore store,
      AutoCloseable documentModule,
      Exception failure) {
    closeOne(virtualProjectSyncScheduler, failure);
    closeOne(operations, failure);
    closeOne(virtualProjectIndexes, failure);
    closeOne(retrieval, failure);
    closeOne(store, failure);
    closeOne(documentModule, failure);
  }

  private static Exception closeOne(AutoCloseable resource, Exception failure) {
    if (resource == null) {
      return failure;
    }
    try {
      resource.close();
    } catch (Exception closeFailure) {
      if (failure == null) {
        return closeFailure;
      }
      failure.addSuppressed(closeFailure);
    }
    return failure;
  }

  @FunctionalInterface
  public interface DocumentModuleFactory {
    SingleWorkspaceDocumentApplication open(Path freshDocumentDirectory) throws Exception;
  }

  public record ToolProfiles(
      RipgrepCodeSearchAdapter.Settings ripgrep,
      CtagsSymbolExtractor.Settings ctags,
      GitEvidenceSettings git) {
    public ToolProfiles {
      Objects.requireNonNull(ripgrep, "ripgrep");
      Objects.requireNonNull(ctags, "ctags");
      Objects.requireNonNull(git, "git");
    }
  }
}
