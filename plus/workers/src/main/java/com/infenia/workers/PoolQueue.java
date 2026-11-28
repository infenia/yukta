// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.service.worker.NoFreeWorkerException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Per-pool queue of attempts that found no free worker slot. A released slot or a new worker calls
 * {@link #wake}, which hands slots to the waiting attempts round-robin across executions, so one
 * run with many waiting nodes cannot starve another run.
 */
final class PoolQueue {

  private final MeterRegistry meters;
  private final ConcurrentMap<String, PoolState> pools = new ConcurrentHashMap<>();

  /* default */ PoolQueue(final MeterRegistry meters) {
    this.meters = meters;
  }

  /**
   * Takes a slot now, or waits for {@link #wake} to hand one over.
   *
   * @param pool the pool of workers
   * @param executionId the run the attempt belongs to, the unit of fairness
   * @param tryTake takes a slot if one is free, with no side effect when none is
   * @param timeout how long to wait
   * @param <T> what a slot is
   * @return the slot, or {@link NoFreeWorkerException} when none frees up in time
   */
  /* default */ <T> Mono<T> acquire(
      final String pool,
      final String executionId,
      final Supplier<Optional<T>> tryTake,
      final Duration timeout) {
    return Mono.defer(() -> {
      final PoolState state = state(pool);
      final Waiter<T> waiter = new Waiter<>(tryTake);
      state.lock.lock();
      try {
        final Optional<T> taken = tryTake.get();
        if (taken.isPresent()) {
          return Mono.just(taken.get());
        }
        state.waiters.computeIfAbsent(executionId, id -> new ArrayDeque<>()).addLast(waiter);
      } finally {
        state.lock.unlock();
      }
      return waiter
          .slot
          .asMono()
          .timeout(timeout)
          .onErrorMap(
              TimeoutException.class,
              e -> {
                state.remove(executionId, waiter);
                return new NoFreeWorkerException(
                    "No free worker in pool '" + pool + "' within " + timeout);
              })
          .doOnCancel(() -> state.remove(executionId, waiter));
    });
  }

  /**
   * Hands free slots to waiting attempts, one execution after the other, until a waiter finds none.
   *
   * @param pool the pool whose slots changed
   */
  /* default */ void wake(final String pool) {
    final PoolState state = pools.get(pool);
    if (state == null) {
      return;
    }
    state.lock.lock();
    try {
      boolean served = true;
      while (served) {
        served = state.serveNext(meters, pool);
      }
    } finally {
      state.lock.unlock();
    }
  }

  /**
   * Counts the attempts waiting for a slot of the pool.
   *
   * @param pool the pool of workers
   * @return the number of waiting attempts
   */
  /* default */ int pending(final String pool) {
    final PoolState state = pools.get(pool);
    return state == null ? 0 : state.size();
  }

  private PoolState state(final String pool) {
    return pools.computeIfAbsent(
        pool,
        name -> {
          final PoolState created = new PoolState();
          Gauge.builder("yukta.pool.pending", created, PoolState::size)
              .tag("pool", name)
              .strongReference(true)
              .register(meters);
          return created;
        });
  }

  /** One attempt that waits for a slot. */
  private static final class Waiter<T> {
    private final Supplier<Optional<T>> tryTake;
    private final Sinks.One<T> slot = Sinks.one();
    private final long enqueuedNanos = System.nanoTime();

    /* default */ Waiter(final Supplier<Optional<T>> tryTake) {
      this.tryTake = tryTake;
    }

    /** Takes a slot for this waiter if one is free; a waiter nobody listens to is left alone. */
    /* default */ boolean serve() {
      if (slot.currentSubscriberCount() == 0) {
        return false;
      }
      final Optional<T> taken = tryTake.get();
      // ponytail: a cancel between the check above and this emit loses the slot; no release hook
      // yet
      taken.ifPresent(slot::tryEmitValue);
      return taken.isPresent();
    }
  }

  /** The waiters of one pool, per execution in order of execution id. */
  private static final class PoolState {
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentMap<String, Deque<Waiter<?>>> waiters = new ConcurrentSkipListMap<>();
    private int cursor;

    /* default */ int size() {
      lock.lock();
      try {
        return waiters.values().stream().mapToInt(Deque::size).sum();
      } finally {
        lock.unlock();
      }
    }

    /* default */ void remove(final String executionId, final Waiter<?> waiter) {
      lock.lock();
      try {
        final Deque<Waiter<?>> queue = waiters.get(executionId);
        if (queue != null && queue.remove(waiter) && queue.isEmpty()) {
          waiters.remove(executionId);
        }
      } finally {
        lock.unlock();
      }
    }

    /** Serves the head waiter of the next execution in turn; false when it found no slot. */
    /* default */ boolean serveNext(final MeterRegistry meters, final String pool) {
      if (waiters.isEmpty()) {
        return false;
      }
      final List<String> executions = new ArrayList<>(waiters.keySet());
      final int at = cursor % executions.size();
      final String executionId = executions.get(at);
      final Deque<Waiter<?>> queue = waiters.get(executionId);
      final Waiter<?> head = queue.peekFirst();
      final boolean served = head.serve();
      if (served) {
        record(meters, pool, System.nanoTime() - head.enqueuedNanos);
        queue.pollFirst();
        // a dropped execution leaves its successor at the same position
        cursor = queue.isEmpty() ? at : at + 1;
        if (queue.isEmpty()) {
          waiters.remove(executionId);
        }
      }
      return served;
    }

    private static void record(final MeterRegistry meters, final String pool, final long nanos) {
      Timer.builder("yukta.pool.wait")
          .tag("pool", pool)
          .register(meters)
          .record(Duration.ofNanos(nanos));
    }
  }
}
