package me.asu.kb.adapter.codesearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Minimal Adapter-local process seam for controlled tests; it is not a general command runner. */
@FunctionalInterface
interface RipgrepProcessLauncher {
  RipgrepRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException;
}
