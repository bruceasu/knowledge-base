package me.asu.kb.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates mutations and synchronization for each virtual project. */
public final class VirtualProjectOperationCoordinator {
  private final ConcurrentMap<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

  /** Acquires the exclusive operation lock for one project. */
  public ProjectLock acquire(String projectId) {
    return acquire(projectId, false);
  }

  /**
   * Tries to acquire the exclusive operation lock for one project.
   *
   * @return a lock when acquired, or {@code null} when another operation owns it
   */
  public ProjectLock tryAcquire(String projectId) {
    return acquire(projectId, true);
  }

  private ProjectLock acquire(String projectId, boolean nonBlocking) {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("projectId must not be blank");
    }
    ReentrantLock lock = projectLocks.computeIfAbsent(projectId, ignored -> new ReentrantLock(true));
    boolean acquired = nonBlocking ? lock.tryLock() : acquireInterruptibly(lock);
    return acquired ? new ProjectLock(lock) : null;
  }

  private static boolean acquireInterruptibly(ReentrantLock lock) {
    try {
      lock.lockInterruptibly();
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("virtual project operation was interrupted", interrupted);
    }
  }

  /** Releases one previously acquired project operation lock. */
  public static final class ProjectLock implements AutoCloseable {
    private final ReentrantLock lock;
    private boolean released;

    private ProjectLock(ReentrantLock lock) {
      this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void close() {
      if (!released) {
        released = true;
        lock.unlock();
      }
    }
  }
}
