package me.asu.kb.adapter.symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Package-private process seam for deterministic Adapter tests. */
interface CtagsProcessLauncher {
  CtagsRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException;
}
