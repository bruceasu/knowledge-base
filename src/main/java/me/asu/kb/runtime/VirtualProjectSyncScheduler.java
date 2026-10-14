package me.asu.kb.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.asu.kb.store.SqliteKnowledgeStore.VirtualProject;

/** Runs bounded, fixed-delay virtual-project synchronization cycles. */
public final class VirtualProjectSyncScheduler implements AutoCloseable {
  public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
  public static final Duration MAX_INTERVAL = Duration.ofDays(1);
  public static final int DEFAULT_MAX_CONCURRENT_PROJECTS = 2;
  public static final int MAX_PROJECTS_PER_CYCLE = 1_000;

  private final VirtualProjectApplication application;
  private final ScheduledExecutorService trigger;
  private final ThreadPoolExecutor workers;
  private final java.util.Set<String> scheduledProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  public VirtualProjectSyncScheduler(
      VirtualProjectApplication application, Duration interval, int maxConcurrentProjects) {
    this.application = Objects.requireNonNull(application, "application");
    Duration boundedInterval = requireInterval(interval);
    if (maxConcurrentProjects < 1) {
      throw new IllegalArgumentException("maxConcurrentProjects must be positive");
    }
    ThreadFactory triggerThreads = namedThreads("knowledge-project-sync-trigger-");
    ThreadFactory workerThreads = namedThreads("knowledge-project-sync-");
    this.trigger = Executors.newSingleThreadScheduledExecutor(triggerThreads);
    this.workers =
        new ThreadPoolExecutor(
            maxConcurrentProjects,
            maxConcurrentProjects,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(16, maxConcurrentProjects * 4)),
            workerThreads,
            new ThreadPoolExecutor.AbortPolicy());
    trigger.scheduleWithFixedDelay(
        this::runCycle,
        boundedInterval.toMillis(),
        boundedInterval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void runCycle() {
    if (closed.get()) {
      return;
    }
    List<Future<?>> submitted = new ArrayList<>();
    try {
      Object result =
          application.execute(
              VirtualProjectApplication.Operation.LIST_PROJECTS,
              Map.of("limit", MAX_PROJECTS_PER_CYCLE));
      if (!(result instanceof VirtualProjectApplication.ProjectList projectList)) {
        return;
      }
      for (VirtualProject project : projectList.projects()) {
        if (!scheduledProjects.add(project.id())) {
          continue;
        }
        try {
          submitted.add(workers.submit(() -> synchronize(project.id())));
        } catch (RejectedExecutionException rejected) {
          scheduledProjects.remove(project.id());
        }
      }
      await(submitted);
    } catch (Exception ignored) {
      // A later fixed-delay cycle retries project discovery or synchronization.
    }
  }

  private void synchronize(String projectId) {
    try {
      application.execute(
          VirtualProjectApplication.Operation.SYNCHRONIZE_PROJECT,
          Map.of("projectId", projectId));
    } catch (Exception ignored) {
      // The synchronization operation persists its own outcome where possible.
    } finally {
      scheduledProjects.remove(projectId);
    }
  }

  private void await(List<Future<?>> submitted) {
    for (Future<?> future : submitted) {
      try {
        future.get();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException ignored) {
        // Individual project failures must not cancel the scheduler.
      }
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    trigger.shutdownNow();
    workers.shutdownNow();
    try {
      workers.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static Duration requireInterval(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    if (interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
      throw new IllegalArgumentException(
          "virtual project sync interval must be between "
              + MIN_INTERVAL
              + " and "
              + MAX_INTERVAL);
    }
    return interval;
  }

  private static ThreadFactory namedThreads(String prefix) {
    AtomicInteger number = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + number.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
