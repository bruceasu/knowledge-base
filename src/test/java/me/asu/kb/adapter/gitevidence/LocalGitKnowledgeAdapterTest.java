package me.asu.kb.adapter.gitevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.asu.kb.core.knowledge.EvidenceType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGitKnowledgeAdapterTest {
  @TempDir Path tempDirectory;

  @Test
  void readsNulSafeWorkspaceRelativeChangesAndAuditMetadata() throws IOException {
    GitRepositoryFixture repository = repository("changes");
    repository.writeText("workspace/base.txt", "one\n");
    repository.writeText("workspace/delete.txt", "delete\n");
    repository.writeText("workspace/rename-old.txt", "rename\n");
    String copyBody = "copy source line\n".repeat(100);
    repository.writeText("workspace/copy-source.txt", copyBody);
    repository.writeText("outside.txt", "outside one\n");
    String parent = repository.commitAll("initial");

    repository.writeText("workspace/base.txt", "two\n");
    repository.delete("workspace/delete.txt");
    repository.move("workspace/rename-old.txt", "workspace/renamed ü name.txt");
    repository.writeText("workspace/copy-source.txt", copyBody + "one changed line\n");
    repository.copy("workspace/copy-source.txt", "workspace/copied file.txt");
    repository.writeBytes("workspace/binary data.bin", new byte[] {0, 1, 2, 3});
    repository.writeText("workspace/空 格.txt", "unicode\n");
    repository.writeText("outside.txt", "outside two\n");
    String commit = repository.commitAll("implements REQ-123 only as metadata");

    GitEvidenceResult result =
        adapter(repository.path("workspace"))
            .inspect(GitEvidenceRequest.explicit("workspace", List.of(commit)));

    assertTrue(result.successful(), () -> result.diagnostics().toString());
    assertEquals(1, result.commits().size());
    GitCommitEvidence evidence = result.commits().get(0);
    assertEquals(commit, evidence.objectId());
    assertEquals(List.of(parent), evidence.parentObjectIds());
    assertEquals("Git Evidence Test", evidence.authorName());
    assertEquals("git-evidence@example.invalid", evidence.authorEmail());
    assertEquals("implements REQ-123 only as metadata", evidence.subject());
    assertEquals(EvidenceType.VERSION_CONTROL, evidence.evidence().type());
    assertTrue(
        evidence.changes().stream()
            .allMatch(change -> change.evidence().type() == EvidenceType.VERSION_CONTROL));
    assertTrue(
        evidence.changes().stream()
            .flatMap(change -> change.oldPath().stream())
            .noneMatch(path -> path.startsWith("outside")));
    assertTrue(
        evidence.changes().stream()
            .flatMap(change -> change.newPath().stream())
            .noneMatch(path -> path.startsWith("outside")));
    assertChange(evidence.changes(), GitChangeKind.MODIFY, "base.txt", "base.txt", false);
    assertChange(evidence.changes(), GitChangeKind.DELETE, "delete.txt", null, false);
    assertChange(
        evidence.changes(), GitChangeKind.RENAME, "rename-old.txt", "renamed ü name.txt", false);
    assertChange(
        evidence.changes(), GitChangeKind.COPY, "copy-source.txt", "copied file.txt", false);
    assertChange(evidence.changes(), GitChangeKind.ADD, null, "binary data.bin", true);
    assertChange(evidence.changes(), GitChangeKind.ADD, null, "空 格.txt", false);
  }

  @Test
  void ordersExplicitBranchSelectionTopologicallyWithObjectIdTieBreaker() throws IOException {
    GitRepositoryFixture repository = repository("branches");
    repository.writeText("root.txt", "root\n");
    String root = repository.commitAll("root");
    repository.createBranch("side");

    repository.writeText("main.txt", "main\n");
    String main = repository.commitAll("main");
    repository.checkout("side");
    repository.writeText("side.txt", "side\n");
    String side = repository.commitAll("side");

    GitEvidenceResult result =
        adapter(repository.root())
            .inspect(GitEvidenceRequest.explicit("workspace", List.of(side, main, root)));

    assertTrue(result.successful(), () -> result.diagnostics().toString());
    List<String> concurrent = new ArrayList<>(List.of(main, side));
    concurrent.sort(Comparator.naturalOrder());
    assertEquals(List.of(root, concurrent.get(0), concurrent.get(1)), objectIds(result));
  }

  @Test
  void appliesChangeBudgetAndRejectsCommandShapedInputAsData() throws IOException {
    GitRepositoryFixture repository = repository("budgets");
    repository.writeText("one.txt", "one\n");
    repository.writeText("two.txt", "two\n");
    String commit = repository.commitAll("two changes");

    GitEvidenceRequest bounded =
        new GitEvidenceRequest(
            "workspace", new ExplicitCommits(List.of(commit)), List.of(), 0, 1);
    GitEvidenceResult boundedResult = adapter(repository.root()).inspect(bounded);
    assertFalse(boundedResult.complete());
    assertEquals(1, boundedResult.commits().get(0).changes().size());
    assertTrue(hasDiagnostic(boundedResult, GitEvidenceDiagnosticCode.CHANGE_TRUNCATED));

    GitEvidenceResult invalidRevision =
        adapter(repository.root())
            .inspect(GitEvidenceRequest.explicit("workspace", List.of("--upload-pack=evil")));
    assertTrue(hasDiagnostic(invalidRevision, GitEvidenceDiagnosticCode.INVALID_SELECTION));

    GitEvidenceRequest invalidPath =
        new GitEvidenceRequest(
            "workspace", new ExplicitCommits(List.of(commit)), List.of("../outside"), 0, 0);
    GitEvidenceResult invalidPathResult = adapter(repository.root()).inspect(invalidPath);
    assertTrue(hasDiagnostic(invalidPathResult, GitEvidenceDiagnosticCode.INVALID_PATH_SCOPE));
  }

  private GitRepositoryFixture repository(String name) throws IOException {
    return GitRepositoryFixture.create(gitExecutable(), tempDirectory.resolve(name));
  }

  private LocalGitKnowledgeAdapter adapter(Path workspace) {
    return new LocalGitKnowledgeAdapter(
        Map.of("workspace", workspace), GitEvidenceSettings.defaults(gitExecutable().toString()));
  }

  private Path gitExecutable() {
    String configured = System.getProperty("git.test.executable", "");
    Assumptions.assumeTrue(!configured.isBlank(), "git.test.executable is required");
    Path executable = Path.of(configured).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isRegularFile(executable), "configured Git executable is missing");
    return executable;
  }

  private static List<String> objectIds(GitEvidenceResult result) {
    return result.commits().stream().map(GitCommitEvidence::objectId).toList();
  }

  private static boolean hasDiagnostic(
      GitEvidenceResult result, GitEvidenceDiagnosticCode code) {
    return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code);
  }

  private static void assertChange(
      List<GitFileChange> changes,
      GitChangeKind kind,
      String oldPath,
      String newPath,
      boolean binary) {
    assertTrue(
        changes.stream()
            .anyMatch(
                change ->
                    change.kind() == kind
                        && Objects.equals(oldPath, change.oldPath().orElse(null))
                        && Objects.equals(newPath, change.newPath().orElse(null))
                        && change.binary() == binary),
        () -> "missing change " + kind + " " + oldPath + " -> " + newPath + ": " + changes);
  }
}
