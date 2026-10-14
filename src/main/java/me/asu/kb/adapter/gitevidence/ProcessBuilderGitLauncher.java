package me.asu.kb.adapter.gitevidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Production launcher for immutable argument-list Git processes. */
final class ProcessBuilderGitLauncher implements GitProcessLauncher {
  @Override
  public GitRunningProcess start(
      List<String> arguments, Path workingDirectory, Map<String, String> environmentOverrides)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(arguments);
    builder.directory(workingDirectory.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
    builder.environment().putAll(environmentOverrides);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process = builder.start();
    try {
      process.getOutputStream().close();
    } catch (IOException error) {
      process.destroyForcibly();
      throw error;
    }
    return new ProcessBackedGitProcess(process);
  }

  private static final class ProcessBackedGitProcess implements GitRunningProcess {
    private final Process process;

    private ProcessBackedGitProcess(Process process) {
      this.process = process;
    }

    @Override
    public InputStream standardOutput() {
      return process.getInputStream();
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public int exitCode() {
      return process.exitValue();
    }

    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public void destroyForcibly() {
      process.destroyForcibly();
    }
  }
}
