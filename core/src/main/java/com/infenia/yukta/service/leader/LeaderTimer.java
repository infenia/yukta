// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * A timer that runs one job at a fixed interval, only while this server is the leader. A tick waits
 * for the previous job to end, so a slow job is not overtaken. Backs the {@code start}, {@code
 * stop} and {@code isRunning} of the lifecycle beans that poll.
 */
public final class LeaderTimer {

  /** Tells whether this server is the leader. */
  private final LeaderElection leader;

  /** Time between two ticks. */
  private final Duration interval;

  /** Runs the timer. */
  private final Scheduler scheduler;

  /** The job of one tick; it must not error. */
  private final Supplier<Mono<Void>> job;

  /** Makes a second start wait for the first, so only one timer runs. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The running timer, or {@code null} when stopped. */
  private final AtomicReference<Disposable> timer = new AtomicReference<>();

  /**
   * Creates the timer.
   *
   * @param leader tells whether this server is the leader
   * @param interval time between two ticks
   * @param scheduler runs the timer; a virtual-time scheduler in tests
   * @param job the job of one tick
   */
  public LeaderTimer(
      final LeaderElection leader,
      final Duration interval,
      final Scheduler scheduler,
      final Supplier<Mono<Void>> job) {
    this.leader = leader;
    this.interval = interval;
    this.scheduler = scheduler;
    this.job = job;
  }

  /** Starts the timer; does nothing when it already runs. */
  public void start() {
    lock.lock();
    try {
      if (timer.get() == null) {
        timer.set(
            Flux.interval(interval, scheduler)
                .filter(_ -> leader.isLeader())
                .concatMap(_ -> job.get())
                .subscribe());
      }
    } finally {
      lock.unlock();
    }
  }

  /** Stops the timer; does nothing when it is stopped. */
  public void stop() {
    final Disposable running = timer.getAndSet(null);
    if (running != null) {
      running.dispose();
    }
  }

  /**
   * Tells whether the timer runs.
   *
   * @return true between {@link #start()} and {@link #stop()}
   */
  public boolean isRunning() {
    return timer.get() != null;
  }
}
