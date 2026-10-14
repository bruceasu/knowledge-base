package me.asu.kb.adapter.gitevidence;

import java.io.InputStream;
import java.time.Duration;

/** Package-private running-process seam for bounded cleanup and deterministic tests. */
interface GitRunningProcess {
  InputStream standardOutput();

  boolean waitFor(Duration timeout) throws InterruptedException;

  int exitCode();

  void destroy();

  void destroyForcibly();
}
