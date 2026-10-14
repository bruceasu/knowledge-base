package me.asu.kb.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic, overflow-safe deadline carried from transport validation to the facade call. */
public final class AgentCallDeadline {
  public static final Duration MAXIMUM_DURATION = Duration.ofSeconds(10);

  private final long deadlineNanos;
  private final LongSupplier nanoTime;

  private AgentCallDeadline(long deadlineNanos, LongSupplier nanoTime) {
    this.deadlineNanos = deadlineNanos;
    this.nanoTime = nanoTime;
  }

  public static AgentCallDeadline start() {
    return start(MAXIMUM_DURATION, System::nanoTime);
  }

  static AgentCallDeadline start(Duration duration, LongSupplier nanoTime) {
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(nanoTime, "nanoTime");
    if (duration.isZero() || duration.isNegative() || duration.compareTo(MAXIMUM_DURATION) > 0) {
      throw new IllegalArgumentException("deadline duration must be between zero and ten seconds");
    }
    long now = nanoTime.getAsLong();
    long durationNanos = duration.toNanos();
    long deadline = now > Long.MAX_VALUE - durationNanos ? Long.MAX_VALUE : now + durationNanos;
    return new AgentCallDeadline(deadline, nanoTime);
  }

  public Duration remaining() throws AgentRequestException {
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    long remainingNanos = deadlineNanos - nanoTime.getAsLong();
    if (remainingNanos <= 0) {
      throw expired();
    }
    return Duration.ofNanos(remainingNanos);
  }

  public void ensureActive() throws AgentRequestException {
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    if (deadlineNanos - nanoTime.getAsLong() <= 0) {
      throw expired();
    }
  }

  private static AgentRequestException cancelled() {
    return new AgentRequestException(
        AgentToolValues.Status.CANCELLED, "CALL_CANCELLED", "tool call was cancelled");
  }

  private static AgentRequestException expired() {
    return new AgentRequestException(
        AgentToolValues.Status.DEADLINE_EXCEEDED,
        "DEADLINE_EXCEEDED",
        "tool call exceeded its deadline");
  }
}
