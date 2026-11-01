// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Finished runs waiting for their cleanup, which is due once their time to live is over. */
@Slf4j
final class ExecutionCleanups {

  /** Time to live for execution data after terminal status. */
  private final Duration ttl;

  /** Finished runs waiting for their cleanup, oldest first (the TTL is the same for all). */
  private final Queue<PendingCleanup> pending = new ConcurrentLinkedQueue<>();

  /** Periodically runs the cleanups that are due. */
  private @Nullable Disposable sweeper;

  /** An execution to clean up once the clock passes {@code dueNanos}. */
  private record PendingCleanup(String executionId, long dueNanos) {}

  /* default */ ExecutionCleanups(final Duration ttl) {
    this.ttl = ttl;
  }

  /** Queues an execution to be cleaned up once its time to live is over from {@code nowNanos}. */
  /* default */ void schedule(final String executionId, final long nowNanos) {
    pending.add(new PendingCleanup(executionId, nowNanos + ttl.toNanos()));
  }

  /** Starts calling {@code sweep} periodically; the period is a fraction of the TTL. */
  /* default */ void startSweeper(final Runnable sweep) {
    // often enough that a run is cleaned soon after its time is up, rarely enough to cost nothing
    final Duration period =
        Duration.ofMillis(Math.clamp(ttl.dividedBy(4).toMillis(), 50L, 10_000L));
    sweeper =
        Flux.interval(period, Schedulers.parallel())
            .concatMap(
                _ ->
                    Mono.fromRunnable(sweep)
                        .onErrorResume(
                            e -> {
                              log.atError().setCause(e).log("Execution cleanup failed; retrying");
                              return Mono.empty();
                            }))
            .subscribe();
  }

  /** Hands every run whose time is up at {@code nowNanos} to {@code cleanup}, oldest first. */
  /* default */ void drainDue(final long nowNanos, final Consumer<String> cleanup) {
    PendingCleanup next = pending.peek();
    while (next != null && nowNanos - next.dueNanos() >= 0) {
      pending.poll();
      cleanup.accept(next.executionId());
      next = pending.peek();
    }
  }

  /** Stops the sweep and forgets the runs still waiting. */
  /* default */ void stop() {
    Optional.ofNullable(sweeper).ifPresent(Disposable::dispose);
    pending.clear();
  }
}
