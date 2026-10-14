package me.asu.kb.adapter.codesearch;

import java.io.InputStream;
import java.time.Duration;

/** Adapter-local lifecycle view of a ripgrep process. */
interface RipgrepRunningProcess {
  InputStream standardOutput();

  boolean waitFor(Duration timeout) throws InterruptedException;

  int exitCode();

  void destroy();

  void destroyForcibly();
}
