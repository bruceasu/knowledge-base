package me.asu.kb.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asu.kb.agent.AgentToolRequestValidator;
import me.asu.kb.runtime.VirtualProjectApplication;
import me.asu.kb.runtime.SingleWorkspaceKnowledgeService;
import me.asu.kb.runtime.SingleWorkspaceLauncher;

/** Standalone local-stdio entry point backed by the production single-workspace composition. */
public final class KnowledgeMcpServerMain {
  private KnowledgeMcpServerMain() {}

  public static void main(String[] args) throws Exception {
    RunningServer running = start(args, System.in, System.out);
    Thread shutdown = new Thread(running::closeQuietly, "knowledge-mcp-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdown);
    try {
      new CountDownLatch(1).await();
    } finally {
      running.close();
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException ignored) {
        // The VM is already running the hook.
      }
    }
  }

  static RunningServer start(String[] args, InputStream input, OutputStream protocolOutput)
      throws Exception {
    SingleWorkspaceLauncher.Configuration configuration = SingleWorkspaceLauncher.load(args);
    SingleWorkspaceKnowledgeService application = SingleWorkspaceLauncher.open(configuration);
    try {
      KnowledgeMcpToolHandlers handlers =
          new KnowledgeMcpToolHandlers(
              application.agentFacade(),
              new AgentToolRequestValidator(
                  configuration.service().workspaceCatalog(),
                  projectId -> {
                    try {
                      application
                          .virtualProjects()
                          .execute(
                              VirtualProjectApplication.Operation.GET_PROJECT,
                              Map.of("projectId", projectId));
                      return true;
                    } catch (VirtualProjectApplication.NotFoundException missing) {
                      return false;
                    }
                  }));
      KnowledgeMcpServer server = KnowledgeMcpServer.start(input, protocolOutput, handlers);
      return new RunningServer(server, application);
    } catch (RuntimeException | Error failure) {
      try {
        application.close();
      } catch (Exception closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  static final class RunningServer implements AutoCloseable {
    private final KnowledgeMcpServer server;
    private final SingleWorkspaceKnowledgeService application;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RunningServer(
        KnowledgeMcpServer server, SingleWorkspaceKnowledgeService application) {
      this.server = server;
      this.application = application;
    }

    List<String> toolNames() {
      return server.toolNames();
    }

    @Override
    public void close() throws Exception {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      Exception failure = null;
      try {
        server.close();
      } catch (RuntimeException closeFailure) {
        failure = closeFailure;
      }
      try {
        application.close();
      } catch (Exception closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }

    private void closeQuietly() {
      try {
        close();
      } catch (Exception failure) {
        System.err.println("knowledge MCP shutdown did not complete");
      }
    }
  }
}
