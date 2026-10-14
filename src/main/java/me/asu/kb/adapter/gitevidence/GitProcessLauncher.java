package me.asu.kb.adapter.gitevidence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Package-private shell-free process seam owned by the Git Adapter. */
interface GitProcessLauncher {
  GitRunningProcess start(
      List<String> arguments, Path workingDirectory, Map<String, String> environmentOverrides)
      throws IOException;
}
