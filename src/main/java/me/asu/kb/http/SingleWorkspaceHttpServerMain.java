package me.asu.kb.http;

import me.asu.kb.runtime.SingleWorkspaceKnowledgeService;
import me.asu.kb.runtime.SingleWorkspaceLauncher;

/** Standalone HTTP/WebUI entry point backed by the production single-workspace composition. */
public final class SingleWorkspaceHttpServerMain {
  private SingleWorkspaceHttpServerMain() {}

  public static void main(String[] args) throws Exception {
    SingleWorkspaceLauncher.Configuration configuration = SingleWorkspaceLauncher.load(args);
    SingleWorkspaceKnowledgeService composition = SingleWorkspaceLauncher.open(configuration);
    SingleWorkspaceHttpApp app = null;
    try {
      app = new SingleWorkspaceHttpApp(composition, configuration.httpPort());
      app.start();
      SingleWorkspaceHttpApp running = app;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(() -> closeQuietly(running), "single-workspace-http-shutdown"));
      Thread.currentThread().join();
    } finally {
      if (app != null) {
        app.close();
      } else {
        composition.close();
      }
    }
  }

  private static void closeQuietly(SingleWorkspaceHttpApp app) {
    try {
      app.close();
    } catch (Exception failure) {
      System.err.println("single-workspace HTTP shutdown did not complete");
    }
  }
}
