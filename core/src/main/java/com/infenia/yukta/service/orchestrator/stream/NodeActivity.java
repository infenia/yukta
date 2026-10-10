// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Activity clock of one node: the basis of its idle timeout.
 *
 * <p>Every node subscribes when the run starts, so a plain {@code Flux.timeout} would count the
 * time the node spends waiting for its upstream. This clock only starts at the node's first
 * activity (an input or an output element) and restarts on every later one, and paused time does
 * not count.
 *
 * <p>{@link #touch()} is a single volatile write, so it is cheap enough to call for every message.
 * One periodic check per node (not one timer per message) compares the last activity with the time
 * it has already counted.
 */
@SuppressWarnings({"PMD.AvoidUsingVolatile", "PMD.OnlyOneReturn"})
public final class NodeActivity {

  /** How many checks make up a timeout. */
  private static final int TICKS = 20;

  /** Shortest check interval, in nanoseconds. */
  private static final long MIN_TICK_NANOS = 1_000_000L;

  /** Marks "no activity yet". */
  private static final long NONE = Long.MIN_VALUE;

  /** The time source for activity stamps. */
  private final Scheduler clock;

  /** Clock reading of the latest activity, or {@link #NONE}. */
  private volatile long lastTouch = NONE;

  /** Clock reading of the first activity, or {@link #NONE}. Starts the node deadline. */
  private volatile long firstTouch = NONE;

  /** Idle timeout set once the first input is known, in nanoseconds; 0 keeps the one given. */
  private volatile long retimed;

  /** Creates a clock that reads time from the parallel scheduler. */
  public NodeActivity() {
    this(Schedulers.parallel());
  }

  /* default */ NodeActivity(final Scheduler clock) {
    this.clock = clock;
  }

  /** Records activity: restarts the idle clock. Safe to call from any thread. */
  public void touch() {
    final long now = clock.now(TimeUnit.NANOSECONDS);
    if (firstTouch == NONE) {
      firstTouch = now;
    }
    lastTouch = now;
  }

  /**
   * Replaces the idle timeout once the first input is known, for a timeout that depends on it. The
   * time already counted is kept.
   *
   * @param timeout the new idle timeout
   */
  public void retime(final Duration timeout) {
    retimed = timeout.toNanos();
  }

  /**
   * Guards a stream with the idle timeout: it fails with a {@link TimeoutException} once the node
   * has been idle for {@code timeout}. The clock stays silent until the first {@link #touch()}.
   *
   * @param source the stream to guard
   * @param timeout the idle time that triggers the timeout
   * @param scheduler the scheduler that runs the periodic check
   * @param paused tells whether the clock must hold
   * @param <T> the element type
   * @return {@code source}, failing with a TimeoutException when idle for too long
   */
  public <T> Flux<T> guard(
      final Flux<T> source,
      final Duration timeout,
      final Scheduler scheduler,
      final BooleanSupplier paused) {
    return guard(source, timeout, null, scheduler, paused);
  }

  /**
   * Guards a stream with the idle timeout and, if given, a deadline: it fails with a {@link
   * TimeoutException} once the node has run for {@code deadline} since its first activity. Unlike
   * the idle timeout the deadline is not restarted by activity or heartbeats; paused time does not
   * count towards it.
   *
   * @param source the stream to guard
   * @param timeout the idle time that triggers the timeout
   * @param deadline the longest the node may run, or null for no deadline
   * @param scheduler the scheduler that runs the periodic checks
   * @param paused tells whether the clocks must hold
   * @param <T> the element type
   * @return {@code source}, failing with a TimeoutException when idle or out of time
   */
  public <T> Flux<T> guard(
      final Flux<T> source,
      final Duration timeout,
      @Nullable final Duration deadline,
      final Scheduler scheduler,
      final BooleanSupplier paused) {
    return Flux.defer(
        () -> {
          final AtomicReference<String> expired = new AtomicReference<>();
          final Mono<Long> idle =
              idle(timeout, scheduler, paused)
                  .doOnNext(_ -> expired.set("Node idle for " + Duration.ofNanos(limit(timeout))));
          final Mono<Long> stop =
              deadline == null
                  ? idle
                  : Mono.firstWithSignal(
                      idle,
                      deadline(deadline, scheduler, paused)
                          .doOnNext(_ -> expired.set("Node exceeded its deadline of " + deadline)));
          return source
              .takeUntilOther(stop)
              .concatWith(
                  Mono.defer(
                      () ->
                          expired.get() == null
                              ? Mono.empty()
                              : Mono.error(new TimeoutException(expired.get()))));
        });
  }

  private long limit(final Duration timeout) {
    final long override = retimed;
    return override > 0 ? override : timeout.toNanos();
  }

  /**
   * Emits once the node has run for {@code deadline} outside pauses, counted from its first touch.
   */
  /* default */ Mono<Long> deadline(
      final Duration deadline, final Scheduler scheduler, final BooleanSupplier paused) {
    return Mono.defer(
        () -> {
          final long limit = deadline.toNanos();
          final long tick = Math.max(MIN_TICK_NANOS, limit / TICKS);
          final long[] running = {0};
          return Flux.interval(Duration.ofNanos(tick), scheduler)
              .filter(
                  _ -> {
                    if (firstTouch != NONE && !paused.getAsBoolean()) {
                      running[0] += tick;
                    }
                    return running[0] >= limit;
                  })
              .next();
        });
  }

  /**
   * A publisher that emits once the node has been idle for {@code timeout}, counting only time
   * outside pauses (to within one twentieth of the timeout, and never early).
   */
  /* default */ Mono<Long> idle(
      final Duration timeout, final Scheduler scheduler, final BooleanSupplier paused) {
    return Mono.defer(
        () -> {
          final long tick = Math.max(MIN_TICK_NANOS, timeout.toNanos() / TICKS);
          // [0] the activity stamp already counted, [1] idle nanoseconds counted so far
          final long[] counted = {NONE, 0};
          return Flux.interval(Duration.ofNanos(tick), scheduler)
              .filter(_ -> expired(counted, tick, limit(timeout), paused))
              .next();
        });
  }

  private boolean expired(
      final long[] counted, final long tick, final long limit, final BooleanSupplier paused) {
    final long last = lastTouch;
    if (last == NONE) {
      return false;
    }
    final boolean running = !paused.getAsBoolean();
    if (last == counted[0]) {
      if (running) {
        counted[1] += tick;
      }
    } else {
      // New activity since the previous check: only the time since it counts.
      counted[0] = last;
      counted[1] = running ? clock.now(TimeUnit.NANOSECONDS) - last : 0;
    }
    return counted[1] >= limit;
  }

  /**
   * A delay that only counts time outside a pause: it is measured in ticks of a twentieth of the
   * delay, and a tick that falls in a pause is not counted. The delay is therefore exact to within
   * one tick of the unpaused time.
   *
   * @param timeout the delay
   * @param scheduler the scheduler that measures time
   * @param paused tells whether the clock must hold
   * @return a Mono that emits when the delay has elapsed outside pauses
   */
  public static Mono<Long> delayUnlessPaused(
      final Duration timeout, final Scheduler scheduler, final BooleanSupplier paused) {
    final long tick = Math.max(MIN_TICK_NANOS, timeout.toNanos() / TICKS);
    final long needed = (timeout.toNanos() + tick - 1) / tick;
    return Flux.interval(Duration.ofNanos(tick), scheduler)
        .filter(_ -> !paused.getAsBoolean())
        .take(needed)
        .then(Mono.just(0L));
  }
}
