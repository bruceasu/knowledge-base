package me.asu.kb.adapter.gitevidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitFailureSemanticsTest {
  private static final String COMMIT_ID = "1111111111111111111111111111111111111111";

  @TempDir Path tempDirectory;

  @Test
  void distinguishesNonGitMissingNonCommitAndShallowHistory() throws IOException {
    Path executable = gitExecutable();
    Path nonGit = Files.createDirectory(tempDirectory.resolve("non-git"));
    GitEvidenceResult nonGitResult = adapter(nonGit, executable).inspect(request(COMMIT_ID));
    assertDiagnostic(nonGitResult, GitEvidenceDiagnosticCode.NOT_GIT_REPOSITORY);

    GitRepositoryFixture repository =
        GitRepositoryFixture.create(executable, tempDirectory.resolve("objects"));
    repository.writeText("file.txt", "one\n");
    String oldest = repository.commitAll("oldest");
    String blob = repository.blobObjectId("file.txt");

    GitEvidenceResult missing =
        adapter(repository.root(), executable)
            .inspect(request("0000000000000000000000000000000000000000"));
    assertDiagnostic(missing, GitEvidenceDiagnosticCode.MISSING_OBJECT);

    GitEvidenceResult nonCommit = adapter(repository.root(), executable).inspect(request(blob));
    assertDiagnostic(nonCommit, GitEvidenceDiagnosticCode.NON_COMMIT_OBJECT);

    repository.writeText("file.txt", "two\n");
    String newest = repository.commitAll("newest");
    repository.markShallowAt(newest);
    GitEvidenceResult shallow =
        adapter(repository.root(), executable)
            .inspect(GitEvidenceRequest.inclusiveRange("workspace", oldest, newest));
    assertDiagnostic(shallow, GitEvidenceDiagnosticCode.SHALLOW_HISTORY);
  }

  @Test
  void distinguishesUnavailableUnexpectedAndMalformedToolOutcomes() throws IOException {
    Path workspace = Files.createDirectory(tempDirectory.resolve("workspace"));
    Path missingExecutable = tempDirectory.resolve("missing-git.exe").toAbsolutePath();
    GitEvidenceResult unavailable =
        adapter(workspace, missingExecutable).inspect(request(COMMIT_ID));
    assertDiagnostic(unavailable, GitEvidenceDiagnosticCode.TOOL_UNAVAILABLE);

    ScriptedLauncher unexpected =
        new ScriptedLauncher(
            process("git version 2.45.1\n", 0),
            process(workspace + "\nsha1\nfalse\n", 0),
            process("", 2));
    GitEvidenceResult unexpectedResult = adapter(workspace, unexpected, 4_096).inspect(request(COMMIT_ID));
    assertDiagnostic(unexpectedResult, GitEvidenceDiagnosticCode.UNEXPECTED_EXIT);

    ScriptedLauncher malformed =
        new ScriptedLauncher(
            process("git version 2.45.1\n", 0), process("only-one-field\n", 0));
    GitEvidenceResult malformedResult = adapter(workspace, malformed, 4_096).inspect(request(COMMIT_ID));
    assertDiagnostic(malformedResult, GitEvidenceDiagnosticCode.MALFORMED_OUTPUT);
  }

  @Test
  void boundsOutputAndCleansUpTimedOutProcesses() throws IOException {
    Path workspace = Files.createDirectory(tempDirectory.resolve("bounded"));
    FakeProcess oversized = process("git version 2.45.1\n", 0);
    ScriptedLauncher outputLauncher = new ScriptedLauncher(oversized);
    GitEvidenceResult outputResult = adapter(workspace, outputLauncher, 4).inspect(request(COMMIT_ID));
    assertDiagnostic(outputResult, GitEvidenceDiagnosticCode.OUTPUT_TRUNCATED);
    assertTrue(oversized.destroyed);

    FakeProcess timedOut = new FakeProcess("git version 2.45.1\n", 0, false);
    ScriptedLauncher timeoutLauncher = new ScriptedLauncher(timedOut);
    GitEvidenceResult timeoutResult = adapter(workspace, timeoutLauncher, 4_096).inspect(request(COMMIT_ID));
    assertDiagnostic(timeoutResult, GitEvidenceDiagnosticCode.TIMEOUT);
    assertTrue(timedOut.destroyed);
    assertTrue(timedOut.destroyedForcibly);
  }

  private LocalGitKnowledgeAdapter adapter(Path workspace, Path executable) {
    return new LocalGitKnowledgeAdapter(
        Map.of("workspace", workspace), GitEvidenceSettings.defaults(executable.toString()));
  }

  private LocalGitKnowledgeAdapter adapter(
      Path workspace, GitProcessLauncher launcher, int outputBytes) {
    GitEvidenceSettings settings =
        new GitEvidenceSettings(
            gitExecutable().toString(), 64, 2_000, outputBytes, Duration.ofSeconds(1));
    return new LocalGitKnowledgeAdapter(Map.of("workspace", workspace), settings, launcher);
  }

  private static GitEvidenceRequest request(String objectId) {
    return GitEvidenceRequest.explicit("workspace", List.of(objectId));
  }

  private Path gitExecutable() {
    String configured = System.getProperty("git.test.executable", "");
    Assumptions.assumeTrue(!configured.isBlank(), "git.test.executable is required");
    Path executable = Path.of(configured).toAbsolutePath().normalize();
    Assumptions.assumeTrue(Files.isRegularFile(executable), "configured Git executable is missing");
    return executable;
  }

  private static void assertDiagnostic(
      GitEvidenceResult result, GitEvidenceDiagnosticCode expected) {
    assertFalse(result.complete());
    assertTrue(
        result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == expected),
        () -> "missing diagnostic " + expected + ": " + result.diagnostics());
  }

  private static FakeProcess process(String output, int exitCode) {
    return new FakeProcess(output, exitCode, true);
  }

  private static final class ScriptedLauncher implements GitProcessLauncher {
    private final Deque<FakeProcess> processes;
    private final List<List<String>> commands = new ArrayList<>();

    private ScriptedLauncher(FakeProcess... processes) {
      this.processes = new ArrayDeque<>(List.of(processes));
    }

    @Override
    public GitRunningProcess start(
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environmentOverrides)
        throws IOException {
      commands.add(List.copyOf(arguments));
      if (processes.isEmpty()) {
        throw new IOException("no scripted process remains");
      }
      return processes.removeFirst();
    }
  }

  private static final class FakeProcess implements GitRunningProcess {
    private final byte[] output;
    private final int exitCode;
    private final boolean finishes;
    private boolean destroyed;
    private boolean destroyedForcibly;

    private FakeProcess(String output, int exitCode, boolean finishes) {
      this.output = output.getBytes(StandardCharsets.UTF_8);
      this.exitCode = exitCode;
      this.finishes = finishes;
    }

    @Override
    public InputStream standardOutput() {
      return new ByteArrayInputStream(output);
    }

    @Override
    public boolean waitFor(Duration timeout) {
      return finishes;
    }

    @Override
    public int exitCode() {
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyed = true;
    }

    @Override
    public void destroyForcibly() {
      destroyedForcibly = true;
    }
  }
}
