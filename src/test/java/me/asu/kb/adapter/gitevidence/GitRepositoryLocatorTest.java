package me.asu.kb.adapter.gitevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryLocatorTest {
  @TempDir Path tempDir;

  @Test
  void detectsRepositoryRootAndRepositoryRelativePath() throws Exception {
    Path executable = configuredGitExecutable();
    GitRepositoryFixture repository =
        GitRepositoryFixture.create(executable, tempDir.resolve("repository"));
    Path file = repository.path("src/Main.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "class Main {}\n");

    GitRepositoryLocator locator =
        new GitRepositoryLocator(GitEvidenceSettings.defaults(executable.toString()));

    GitRepositoryLocator.Location location = locator.locate(file).orElseThrow();

    assertEquals(repository.root().toString(), location.repositoryRoot());
    assertEquals("src/Main.java", location.relativePath());
  }

  @Test
  void treatsNonGitPathsAsUnprovenanced() throws Exception {
    Path executable = configuredGitExecutable();
    Path file = Files.createFile(tempDir.resolve("outside.txt"));
    GitRepositoryLocator locator =
        new GitRepositoryLocator(GitEvidenceSettings.defaults(executable.toString()));

    assertTrue(locator.locate(file).isEmpty());
  }

  private static Path configuredGitExecutable() {
    String configured = System.getProperty("git.test.executable", "");
    Assumptions.assumeTrue(!configured.isBlank(), "git.test.executable is required");
    Path executable = Path.of(configured);
    Assumptions.assumeTrue(Files.isRegularFile(executable), "configured Git executable is missing");
    return executable;
  }
}
