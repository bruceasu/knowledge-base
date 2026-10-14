package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import me.asu.kb.adapter.codesearch.RipgrepCodeSearchAdapter;
import me.asu.kb.adapter.gitevidence.GitEvidenceSettings;
import me.asu.kb.adapter.symbol.CtagsSymbolExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleWorkspaceKnowledgeServiceTest {
  @TempDir Path tempDir;

  @Test
  void opensOneSharedApplicationGraphAndClosesTheDocumentModuleOnce() throws Exception {
    Path root = tempDir.resolve("workspace");
    SingleWorkspaceServiceConfig config =
        SingleWorkspaceServiceConfig.parse(
            new String[] {"--workspace-root", root.toString()}, Map.of());
    AtomicInteger documentCloseCount = new AtomicInteger();
    Path unavailableCtags = tempDir.resolve("tools/ctags.exe").toAbsolutePath();
    Path unavailableGit = tempDir.resolve("tools/git.exe").toAbsolutePath();
    var profiles =
        new SingleWorkspaceKnowledgeService.ToolProfiles(
            RipgrepCodeSearchAdapter.Settings.defaults(),
            CtagsSymbolExtractor.Settings.defaults(unavailableCtags.toString()),
            GitEvidenceSettings.defaults(unavailableGit.toString()));

    SingleWorkspaceKnowledgeService service =
        SingleWorkspaceKnowledgeService.open(
            config,
            documentDirectory -> {
              assertTrue(documentDirectory.startsWith(root));
              return new SingleWorkspaceDocumentApplication() {
                @Override
                public Object execute(Operation operation, Map<String, Object> arguments) {
                  return Map.of("ok", true);
                }

                @Override
                public void close() {
                  documentCloseCount.incrementAndGet();
                }
              };
            },
            profiles);

    assertNotNull(service.projectionIngestion());
    assertNotNull(service.operationCoordinator());
    assertTrue(service.readiness().ready());
    assertNotNull(service.retriever());
    assertNotNull(service.objectReader());
    assertNotNull(service.relationTracer());
    assertNotNull(service.contextBuilder());
    assertNotNull(service.agentFacade());

    service.close();
    service.close();
    assertEquals(ServiceLifecycleState.CLOSED, service.readiness().state());
    assertFalse(service.readiness().ready());
    assertEquals(1, documentCloseCount.get());
  }
}
