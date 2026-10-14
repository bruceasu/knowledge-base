package me.asu.kb.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImmutableEngineeringWorkspaceCatalogTest {
  @TempDir Path tempDir;

  @Test
  void providesStableNormalizedLookupAndDeterministicListing() {
    Path betaRoot = tempDir.resolve("beta").resolve("..").resolve("beta");
    Path alphaRoot = tempDir.resolve("alpha");
    ImmutableEngineeringWorkspaceCatalog catalog =
        new ImmutableEngineeringWorkspaceCatalog(
            List.of(
                new EngineeringWorkspace("beta", betaRoot),
                new EngineeringWorkspace("alpha", alphaRoot)));

    EngineeringWorkspace beta = catalog.find("beta").orElseThrow();

    assertEquals(betaRoot.toAbsolutePath().normalize(), beta.root());
    assertEquals(List.of("alpha", "beta"), catalog.workspaces().stream().map(EngineeringWorkspace::id).toList());
    assertTrue(catalog.find("missing").isEmpty());
    assertTrue(catalog.find(null).isEmpty());
  }

  @Test
  void snapshotsCallerCollectionsAndReturnsImmutableDescriptions() {
    Map<String, Path> roots = new LinkedHashMap<>();
    roots.put("workspace", tempDir.resolve("workspace"));
    ImmutableEngineeringWorkspaceCatalog catalog =
        ImmutableEngineeringWorkspaceCatalog.fromRoots(roots);

    roots.clear();

    assertTrue(catalog.find("workspace").isPresent());
    assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.workspaces().add(new EngineeringWorkspace("other", tempDir)));
  }

  @Test
  void rejectsInvalidAndDuplicateWorkspaceIds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EngineeringWorkspace("../outside", tempDir));
    EngineeringWorkspace first = new EngineeringWorkspace("same", tempDir.resolve("first"));
    EngineeringWorkspace second = new EngineeringWorkspace("same", tempDir.resolve("second"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableEngineeringWorkspaceCatalog(List.of(first, second)));
  }
}
