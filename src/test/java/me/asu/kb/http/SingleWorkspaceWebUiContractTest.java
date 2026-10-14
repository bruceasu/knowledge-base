package me.asu.kb.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingleWorkspaceWebUiContractTest {
  private static final List<String> ASSETS =
      List.of("index.html", "manage.html", "app.js", "manage.js", "styles.css");

  @Test
  void sourceAndBundledAssetsAreIdentical() throws IOException {
    for (String asset : ASSETS) {
      byte[] source = Files.readAllBytes(Path.of("webui", asset));
      byte[] bundled = Files.readAllBytes(Path.of("src/main/resources/webui", asset));
      assertArrayEquals(source, bundled, "out-of-sync asset " + asset);
    }
    assertFalse(Files.exists(Path.of("src/main/resources/webui/workspace.js")));
  }

  @Test
  void retainedBrowserFlowsUseOnlyWorkspaceFreeEndpoints() throws IOException {
    String scripts =
        Files.readString(Path.of("webui/app.js"), StandardCharsets.UTF_8)
            + Files.readString(Path.of("webui/manage.js"), StandardCharsets.UTF_8);

    assertFalse(scripts.contains("/api/v1"));
    assertFalse(scripts.contains("workspaceId"));
    assertFalse(scripts.contains("workspaceUrl"));
    assertFalse(scripts.contains("workspaceBody"));
    for (String endpoint :
        List.of(
            "/api/v2/health",
            "/api/v2/status",
            "/api/v2/knowledge/search",
            "/api/v2/knowledge/objects/",
            "/api/v2/ask",
            "/api/v2/sessions",
            "/api/v2/documents",
            "/api/v2/ingest/file",
            "/api/v2/ingest/dir",
            "/api/v2/index/rebuild",
            "/api/v2/roots",
            "/api/v2/virtual-projects")) {
      assertTrue(scripts.contains(endpoint), "missing retained endpoint " + endpoint);
    }
    assertTrue(scripts.contains("aria-busy"));
    assertTrue(scripts.contains("freshness"));
    assertTrue(scripts.contains("projectFeedback"));
  }
}
