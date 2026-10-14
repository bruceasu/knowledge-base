package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualProjectPathPolicyTest {
  @TempDir Path tempDir;

  @Test
  void workspaceIsAllowedButExternalPathsAreDeniedByDefault() throws IOException {
    Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
    Path inside = Files.createFile(workspace.resolve("notes.md"));
    Path outside = Files.createFile(tempDir.resolve("outside.md"));
    VirtualProjectPathPolicy policy = new VirtualProjectPathPolicy(workspace);

    assertTrue(policy.validate(inside).allowed());
    assertEquals(
        VirtualProjectPathPolicy.DenialReason.OUTSIDE_ALLOW_LIST,
        policy.validate(outside).reason());
  }

  @Test
  void forbidWinsOverAllowAndUsesPathComponentBoundaries() throws IOException {
    Path allowedRoot = Files.createDirectory(tempDir.resolve("allowed"));
    Path forbiddenRoot = Files.createDirectory(allowedRoot.resolve("private"));
    Path forbiddenFile = Files.createFile(forbiddenRoot.resolve("secret.md"));
    Path similarlyNamed = Files.createDirectory(tempDir.resolve("allowed-private"));
    Path siblingFile = Files.createFile(similarlyNamed.resolve("public.md"));
    VirtualProjectPathPolicy policy =
        new VirtualProjectPathPolicy(
            tempDir.resolve("workspace"), List.of(allowedRoot), List.of(forbiddenRoot));

    assertEquals(
        VirtualProjectPathPolicy.DenialReason.FORBIDDEN,
        policy.validate(forbiddenFile).reason());
    assertTrue(policy.validate(siblingFile).allowed());
  }

  @Test
  void validatesMissingTargetsUsingTheCanonicalExistingParent() throws IOException {
    Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
    Path externalRoot = Files.createDirectory(tempDir.resolve("external"));
    VirtualProjectPathPolicy policy =
        new VirtualProjectPathPolicy(workspace, List.of(externalRoot), List.of());
    Path missing = externalRoot.resolve("new").resolve("future.md");

    VirtualProjectPathPolicy.PathValidation result = policy.validate(missing);

    assertTrue(result.allowed());
    assertEquals(missing.toAbsolutePath().normalize(), result.canonicalPath().orElseThrow());
  }

  @Test
  void resolvesSymlinkTargetsBeforeApplyingRules() throws IOException {
    Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
    Path allowedRoot = Files.createDirectory(tempDir.resolve("allowed"));
    Path forbiddenRoot = Files.createDirectory(tempDir.resolve("forbidden"));
    Path forbiddenFile = Files.createFile(forbiddenRoot.resolve("secret.md"));
    Path link = allowedRoot.resolve("linked-secret.md");
    try {
      Files.createSymbolicLink(link, forbiddenFile);
    } catch (UnsupportedOperationException | SecurityException | IOException error) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable in this environment");
    }
    VirtualProjectPathPolicy policy =
        new VirtualProjectPathPolicy(workspace, List.of(allowedRoot), List.of(forbiddenRoot));

    assertFalse(policy.validate(link).allowed());
    assertEquals(
        VirtualProjectPathPolicy.DenialReason.FORBIDDEN, policy.validate(link).reason());
  }

  @Test
  void deniedDiagnosticsDoNotExposeLocalPaths() throws IOException {
    Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
    Path outside = Files.createFile(tempDir.resolve("sensitive-name.md"));
    VirtualProjectPathPolicy.PathValidation result =
        new VirtualProjectPathPolicy(workspace).validate(outside);

    assertFalse(result.allowed());
    assertFalse(result.diagnostic().contains(tempDir.toString()));
    assertFalse(result.diagnostic().contains(outside.toString()));
  }
}
