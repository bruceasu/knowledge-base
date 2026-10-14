package me.asu.kb.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The single bounded executor and shutdown admission gate for application operations. */
public final class ServiceOperationCoordinator implements AutoCloseable {
  public static final int DEFAULT_MAX_THREADS = 4;
  public static final int DEFAULT_QUEUE_CAPACITY = 128;
  public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(10);

  private final ThreadPoolExecutor executor;
  private final Duration shutdownGrace;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();

  public ServiceOperationCoordinator() {
    this(DEFAULT_MAX_THREADS, DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_GRACE);
  }

  ServiceOperationCoordinator(int maxThreads, int queueCapacity, Duration shutdownGrace) {
    if (maxThreads < 1) {
      throw new IllegalArgumentException("maxThreads must be positive");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }
    this.shutdownGrace = requirePositive(shutdownGrace, "shutdownGrace");
    AtomicInteger threadNumber = new AtomicInteger();
    ThreadFactory threads =
        task -> {
          Thread thread =
              new Thread(task, "knowledge-operation-" + threadNumber.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    this.executor =
        new ThreadPoolExecutor(
            maxThreads,
            maxThreads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            threads,
            new ThreadPoolExecutor.AbortPolicy());
  }

  /** Submit one bounded call. The caller-provided deadline also bounds its wait. */
  public <T> T execute(Callable<T> operation, Duration deadline)
      throws InterruptedException, ExecutionException, TimeoutException {
    Objects.requireNonNull(operation, "operation");
    Duration boundedDeadline = requirePositive(deadline, "deadline");
    if (!accepting.get()) {
      throw new RejectedExecutionException("service is not accepting new operations");
    }

    Future<T> future;
    try {
      future = executor.submit(operation);
    } catch (RejectedExecutionException rejected) {
      throw new RejectedExecutionException("service operation capacity is unavailable", rejected);
    }
    try {
      return future.get(boundedDeadline.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (TimeoutException timeout) {
      future.cancel(true);
      throw timeout;
    }
  }

  public int queueDepth() {
    return executor.getQueue().size();
  }

  public boolean isAccepting() {
    return accepting.get();
  }

  /** Close admission immediately without interrupting work that was already accepted. */
  public void stopAccepting() {
    if (accepting.compareAndSet(true, false)) {
      executor.shutdown();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    stopAccepting();
    try {
      if (!executor.awaitTermination(shutdownGrace.toNanos(), TimeUnit.NANOSECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
