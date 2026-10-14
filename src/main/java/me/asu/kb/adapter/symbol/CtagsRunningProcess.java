package me.asu.kb.adapter.symbol;

import java.io.InputStream;
import java.time.Duration;

/** Package-private running-process seam owned by the Ctags Adapter. */
interface CtagsRunningProcess {
  InputStream standardOutput();

  boolean waitFor(Duration timeout) throws InterruptedException;

  int exitCode();

  void destroy();

  void destroyForcibly();
}
