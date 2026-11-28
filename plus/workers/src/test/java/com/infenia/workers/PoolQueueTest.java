// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.worker.NoFreeWorkerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

/** Attempts waiting for a free slot in their pool, served round-robin across runs. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class PoolQueueTest {

  private static final String POOL = "p";

  @Test
  @DisplayName("[T1] acquire takes a free slot at once")
  void acquire_freeSlot_takesValueAtOnce() {
    final PoolQueue queue = new PoolQueue(new SimpleMeterRegistry());

    StepVerifier.create(queue.acquire(POOL, "e", () -> Optional.of("slot"), Duration.ofSeconds(1)))
        .expectNext("slot")
        .verifyComplete();

    assertThat(queue.pending(POOL)).isZero();
  }

  @Test
  @DisplayName("[T2] acquire waiter is woken by a released slot")
  void acquire_waiterWokenBySlot_completesWithValue() {
    final PoolQueue queue = new PoolQueue(new SimpleMeterRegistry());
    final AtomicBoolean free = new AtomicBoolean(false);
    final Supplier<Optional<String>> tryTake = () -> Optional.of("slot").filter(v -> free.get());

    StepVerifier.create(queue.acquire(POOL, "e", tryTake, Duration.ofSeconds(5)))
        .then(() -> {
          free.set(true);
          queue.wake(POOL);
        })
        .expectNext("slot")
        .verifyComplete();
  }

  @Test
  @DisplayName("[T3] acquire times out with NoFreeWorkerException after one second")
  void acquire_noSlotUntilTimeout_failsWithNoFreeWorker() {
    final PoolQueue queue = new PoolQueue(new SimpleMeterRegistry());
    final Supplier<Optional<String>> none = Optional::empty;

    StepVerifier.withVirtualTime(() -> queue.acquire(POOL, "e", none, Duration.ofSeconds(1)))
        .thenAwait(Duration.ofSeconds(1))
        .expectError(NoFreeWorkerException.class)
        .verify();

    assertThat(queue.pending(POOL)).isZero();
  }

  @Test
  @DisplayName("[T4] wake serves the first waiter of run A then the waiter of run B")
  void wake_twoExecutionsQueued_servesFirstThenSecondExecution() {
    final PoolQueue queue = new PoolQueue(new SimpleMeterRegistry());
    final AtomicInteger free = new AtomicInteger();
    final List<String> served = new CopyOnWriteArrayList<>();

    queue.acquire(POOL, "A", freeSlotFor("A1", free), Duration.ofMinutes(1)).subscribe(served::add);
    queue.acquire(POOL, "A", freeSlotFor("A2", free), Duration.ofMinutes(1)).subscribe(served::add);
    queue.acquire(POOL, "A", freeSlotFor("A3", free), Duration.ofMinutes(1)).subscribe(served::add);
    queue.acquire(POOL, "B", freeSlotFor("B1", free), Duration.ofMinutes(1)).subscribe(served::add);

    free.set(1);
    queue.wake(POOL);
    free.set(1);
    queue.wake(POOL);

    assertThat(served).containsExactly("A1", "B1");
  }

  @Test
  @DisplayName("[T5] cancelled acquire waiter leaves the queue")
  void acquire_cancelledWaiter_leavesQueue() {
    final PoolQueue queue = new PoolQueue(new SimpleMeterRegistry());
    final Supplier<Optional<String>> none = Optional::empty;

    final Disposable subscription =
        queue.acquire(POOL, "e", none, Duration.ofMinutes(1)).subscribe();
    assertThat(queue.pending(POOL)).isEqualTo(1);

    subscription.dispose();

    assertThat(queue.pending(POOL)).isZero();
  }

  /** Takes one unit from {@code free} for the waiter labelled {@code label}, if any is left. */
  private static Supplier<Optional<String>> freeSlotFor(
      final String label, final AtomicInteger free) {
    return () -> Optional.of(label).filter(l -> free.getAndUpdate(n -> Math.max(0, n - 1)) > 0);
  }
}
