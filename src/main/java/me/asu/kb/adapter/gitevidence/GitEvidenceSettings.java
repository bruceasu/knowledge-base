package me.asu.kb.adapter.gitevidence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Immutable configuration and hard ceilings for the local read-only Git profile. */
public record GitEvidenceSettings(
    String executable,
    int defaultMaxCommits,
    int defaultMaxChanges,
    int maxOutputBytes,
    Duration timeout) {
  public static final int HARD_MAX_COMMITS = 256;
  public static final int HARD_MAX_CHANGES = 10_000;
  public static final int HARD_MAX_OUTPUT_BYTES = 16_777_216;
  public static final Duration HARD_MAX_TIMEOUT = Duration.ofSeconds(60);

  public GitEvidenceSettings {
    executable = executable == null ? "" : executable;
    try {
      if (!executable.isBlank() && !Path.of(executable).isAbsolute()) {
        throw new IllegalArgumentException("executable must be an absolute path");
      }
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("executable must be a valid absolute path", error);
    }
    if (defaultMaxCommits < 1 || defaultMaxCommits > HARD_MAX_COMMITS) {
      throw new IllegalArgumentException("defaultMaxCommits must be between 1 and 256");
    }
    if (defaultMaxChanges < 1 || defaultMaxChanges > HARD_MAX_CHANGES) {
      throw new IllegalArgumentException("defaultMaxChanges must be between 1 and 10000");
    }
    if (maxOutputBytes < 1 || maxOutputBytes > HARD_MAX_OUTPUT_BYTES) {
      throw new IllegalArgumentException("maxOutputBytes must be between 1 and 16777216");
    }
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(HARD_MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException("timeout must be positive and no longer than 60 seconds");
    }
  }

  public static GitEvidenceSettings defaults(String executable) {
    return new GitEvidenceSettings(
        executable, 64, 2_000, 4_194_304, Duration.ofSeconds(10));
  }

  public static GitEvidenceSettings unavailable() {
    return defaults("");
  }
}
