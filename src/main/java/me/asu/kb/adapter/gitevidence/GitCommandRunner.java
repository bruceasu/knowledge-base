package me.asu.kb.adapter.gitevidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes only Adapter-constructed Git arguments under the fixed local-readonly profile. */
final class GitCommandRunner {
  static final String PROFILE_ID = "git-local-readonly-v1";
  private static final Duration TERMINATION_GRACE = Duration.ofMillis(250);
  private static final List<String> FIXED_GLOBAL_ARGUMENTS =
      List.of(
          "--no-pager",
          "--literal-pathspecs",
          "-c",
          "core.pager=cat",
          "-c",
          "color.ui=false");
  private static final Map<String, String> FIXED_ENVIRONMENT =
      Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0");

  private final String executable;
  private final GitProcessLauncher launcher;

  GitCommandRunner(String executable, GitProcessLauncher launcher) {
    this.executable = executable;
    this.launcher = launcher;
  }

  GitCommandOutput execute(
      Path workingDirectory, List<String> adapterArguments, GitExecutionBudget budget)
      throws GitProbeException {
    budget.remainingTime();
    List<String> command = new ArrayList<>(1 + FIXED_GLOBAL_ARGUMENTS.size() + adapterArguments.size());
    command.add(executable);
    command.addAll(FIXED_GLOBAL_ARGUMENTS);
    command.addAll(adapterArguments);

    final GitRunningProcess process;
    try {
      process = launcher.start(List.copyOf(command), workingDirectory, FIXED_ENVIRONMENT);
    } catch (IOException | SecurityException error) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.TOOL_UNAVAILABLE, "configured Git executable could not start");
    }

    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "git-evidence-output");
          thread.setDaemon(true);
          return thread;
        };
    ExecutorService reader = Executors.newSingleThreadExecutor(factory);
    boolean completed = false;
    try {
      int allowance = budget.remainingOutputBytes();
      Future<BoundedOutput> future =
          reader.submit(() -> readBounded(process.standardOutput(), allowance));
      BoundedOutput output;
      try {
        output =
            future.get(
                Math.max(1L, budget.remainingTime().toNanos()), TimeUnit.NANOSECONDS);
      } catch (TimeoutException error) {
        throw new GitProbeException(
            GitEvidenceDiagnosticCode.TIMEOUT, "Git inspection exceeded its total deadline");
      } catch (ExecutionException error) {
        throw new GitProbeException(
            GitEvidenceDiagnosticCode.MALFORMED_OUTPUT, "Git stdout could not be read");
      }
      if (output.truncated()) {
        throw new GitProbeException(
            GitEvidenceDiagnosticCode.OUTPUT_TRUNCATED,
            "Git inspection exceeded its accumulated stdout budget");
      }
      budget.consumeOutputBytes(output.bytes().length);
      if (!process.waitFor(budget.remainingTime())) {
        throw new GitProbeException(
            GitEvidenceDiagnosticCode.TIMEOUT, "Git inspection exceeded its total deadline");
      }
      completed = true;
      return new GitCommandOutput(process.exitCode(), output.bytes());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.TIMEOUT, "Git inspection was interrupted");
    } finally {
      if (!completed) {
        terminate(process);
      }
      reader.shutdownNow();
    }
  }

  private static BoundedOutput readBounded(InputStream output, int byteLimit) throws IOException {
    try (InputStream input = output; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4_096];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        int remaining = byteLimit - bytes.size();
        if (read > remaining) {
          if (remaining > 0) {
            bytes.write(buffer, 0, remaining);
          }
          return new BoundedOutput(bytes.toByteArray(), true);
        }
        bytes.write(buffer, 0, read);
      }
      return new BoundedOutput(bytes.toByteArray(), false);
    }
  }

  private static void terminate(GitRunningProcess process) {
    process.destroy();
    try {
      if (!process.waitFor(TERMINATION_GRACE)) {
        process.destroyForcibly();
        process.waitFor(TERMINATION_GRACE);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private record BoundedOutput(byte[] bytes, boolean truncated) {}
}
