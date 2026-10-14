package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceOperationCoordinatorTest {
  @Test
  void closingRejectsNewWorkAndDrainsAcceptedWork() throws Exception {
    ServiceOperationCoordinator coordinator =
        new ServiceOperationCoordinator(1, 1, Duration.ofSeconds(2));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> callerFailure = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                coordinator.execute(
                    () -> {
                      started.countDown();
                      release.await();
                      return "done";
                    },
                    Duration.ofSeconds(3));
              } catch (Exception failure) {
                callerFailure.set(failure);
              }
            });
    caller.start();
    assertTrue(started.await(1, TimeUnit.SECONDS));

    Thread closer = new Thread(coordinator::close);
    closer.start();
    while (coordinator.isAccepting()) {
      Thread.onSpinWait();
    }
    assertThrows(
        RejectedExecutionException.class,
        () -> coordinator.execute(() -> "late", Duration.ofSeconds(1)));
    release.countDown();
    closer.join(2_000);
    caller.join(2_000);

    assertFalse(closer.isAlive());
    assertFalse(caller.isAlive());
    assertEquals(null, callerFailure.get());
  }

  @Test
  void operationDeadlineInterruptsWorker() throws Exception {
    ServiceOperationCoordinator coordinator =
        new ServiceOperationCoordinator(1, 1, Duration.ofSeconds(1));
    CountDownLatch interrupted = new CountDownLatch(1);
    try {
      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () ->
              coordinator.execute(
                  () -> {
                    try {
                      Thread.sleep(5_000);
                    } catch (InterruptedException expected) {
                      interrupted.countDown();
                      Thread.currentThread().interrupt();
                    }
                    return null;
                  },
                  Duration.ofMillis(20)));
      assertTrue(interrupted.await(1, TimeUnit.SECONDS));
      assertEquals(0, coordinator.queueDepth());
    } finally {
      coordinator.close();
    }
  }
}
