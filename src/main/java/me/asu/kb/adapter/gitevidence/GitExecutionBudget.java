package me.asu.kb.adapter.gitevidence;

import java.time.Duration;

/** One accumulated stdout and deadline budget shared by every process in an inspection. */
final class GitExecutionBudget {
  private final long deadlineNanos;
  private final int maximumOutputBytes;
  private int consumedOutputBytes;

  GitExecutionBudget(Duration timeout, int maximumOutputBytes) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (maximumOutputBytes < 1) {
      throw new IllegalArgumentException("maximumOutputBytes must be positive");
    }
    long now = System.nanoTime();
    long timeoutNanos = timeout.toNanos();
    this.deadlineNanos =
        now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    this.maximumOutputBytes = maximumOutputBytes;
  }

  Duration remainingTime() throws GitProbeException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.TIMEOUT, "Git inspection exceeded its total deadline");
    }
    return Duration.ofNanos(remaining);
  }

  int remainingOutputBytes() throws GitProbeException {
    int remaining = maximumOutputBytes - consumedOutputBytes;
    if (remaining <= 0) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.OUTPUT_TRUNCATED,
          "Git inspection exhausted its accumulated stdout budget");
    }
    return remaining;
  }

  void consumeOutputBytes(int count) throws GitProbeException {
    if (count < 0 || count > maximumOutputBytes - consumedOutputBytes) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.OUTPUT_TRUNCATED,
          "Git inspection exceeded its accumulated stdout budget");
    }
    consumedOutputBytes += count;
  }
}
