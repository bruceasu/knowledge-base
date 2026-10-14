package me.asu.kb.adapter.gitevidence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Local-only temporary Git repository fixture; it never reads or mutates a user repository. */
final class GitRepositoryFixture {
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);

  private final Path executable;
  private final Path root;
  private int commitSequence;

  private GitRepositoryFixture(Path executable, Path root) {
    this.executable = executable;
    this.root = root;
  }

  static GitRepositoryFixture create(Path executable, Path root) throws IOException {
    Files.createDirectories(root);
    GitRepositoryFixture fixture = new GitRepositoryFixture(executable, root.toRealPath());
    fixture.run("init", "--initial-branch=main");
    fixture.run("config", "user.name", "Git Evidence Test");
    fixture.run("config", "user.email", "git-evidence@example.invalid");
    fixture.run("config", "core.autocrlf", "false");
    fixture.run("config", "commit.gpgsign", "false");
    return fixture;
  }

  Path root() {
    return root;
  }

  Path path(String relativePath) {
    return root.resolve(relativePath.replace('/', java.io.File.separatorChar));
  }

  void writeText(String relativePath, String content) throws IOException {
    Path target = path(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  void writeBytes(String relativePath, byte[] content) throws IOException {
    Path target = path(relativePath);
    Files.createDirectories(target.getParent());
    Files.write(target, content);
  }

  void move(String oldRelativePath, String newRelativePath) throws IOException {
    Path target = path(newRelativePath);
    Files.createDirectories(target.getParent());
    Files.move(path(oldRelativePath), target, StandardCopyOption.REPLACE_EXISTING);
  }

  void copy(String sourceRelativePath, String targetRelativePath) throws IOException {
    Path target = path(targetRelativePath);
    Files.createDirectories(target.getParent());
    Files.copy(path(sourceRelativePath), target, StandardCopyOption.REPLACE_EXISTING);
  }

  void delete(String relativePath) throws IOException {
    Files.delete(path(relativePath));
  }

  String commitAll(String subject) {
    run("add", "-A");
    commitSequence++;
    String timestamp = String.format("2026-01-%02dT00:00:00Z", commitSequence);
    runWithEnvironment(
        Map.of("GIT_AUTHOR_DATE", timestamp, "GIT_COMMITTER_DATE", timestamp),
        "commit",
        "--no-gpg-sign",
        "-m",
        subject);
    return head();
  }

  String head() {
    return run("rev-parse", "HEAD").strip();
  }

  void createBranch(String name) {
    run("branch", name);
  }

  void checkout(String name) {
    run("checkout", "--quiet", name);
  }

  void mergeNoFastForward(String name, String subject) {
    commitSequence++;
    String timestamp = String.format("2026-01-%02dT00:00:00Z", commitSequence);
    runWithEnvironment(
        Map.of("GIT_AUTHOR_DATE", timestamp, "GIT_COMMITTER_DATE", timestamp),
        "merge",
        "--no-ff",
        "--no-edit",
        "-m",
        subject,
        name);
  }

  String objectId(String revision) {
    return run("rev-parse", revision).strip();
  }

  String blobObjectId(String relativePath) {
    return run("hash-object", relativePath).strip();
  }

  void markShallowAt(String objectId) throws IOException {
    Files.writeString(path(".git/shallow"), objectId + "\n", StandardCharsets.US_ASCII);
  }

  private String run(String... arguments) {
    return runWithEnvironment(Map.of(), arguments);
  }

  private String runWithEnvironment(Map<String, String> environment, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(root.toFile());
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true);
    try {
      Process process = builder.start();
      process.getOutputStream().close();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (InputStream input = process.getInputStream()) {
        input.transferTo(output);
      }
      boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
      }
      assertTrue(finished, () -> "fixture Git command timed out: " + command);
      assertTrue(
          process.exitValue() == 0,
          () ->
              "fixture Git command failed: "
                  + command
                  + System.lineSeparator()
                  + output.toString(StandardCharsets.UTF_8));
      return output.toString(StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("fixture Git command could not start", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture Git command was interrupted", error);
    }
  }
}
