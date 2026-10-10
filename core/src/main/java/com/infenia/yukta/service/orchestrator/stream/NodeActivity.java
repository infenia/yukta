// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * Activity clock of one node: the basis of its idle timeout.
 *
 * <p>Every node subscribes when the run starts, so a plain {@code Flux.timeout} would count the
 * time the node spends waiting for its upstream. This clock only starts at the node's first
 * activity (an input or an output element) and restarts on every later one, and it never expires
 * while the node or run is paused.
 */
@NoArgsConstructor
public final class NodeActivity {

  /** Replays the latest touch, so a late subscriber still sees the last activity. */
  private final Sinks.Many<Boolean> touches = Sinks.many().replay().latest();

  /** Records activity: restarts the idle clock. Safe to call from any thread. */
  public void touch() {
    touches.emitNext(Boolean.TRUE, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
  }

  /**
   * A publisher that emits once the node has been idle for {@code timeout}.
   *
   * <p>It stays silent until the first {@link #touch()}. If the node is paused when a period ends,
   * a fresh period starts instead, so a pause never fails the node; the timeout can therefore fire
   * up to one extra period after a resume.
   *
   * @param timeout the idle time that triggers the timeout
   * @param scheduler the scheduler that measures time
   * @param paused tells whether the clock must hold
   * @return a Mono that emits when the idle timeout expires
   */
  public Mono<Long> idle(
      final Duration timeout, final Scheduler scheduler, final BooleanSupplier paused) {
    return touches.asFlux().switchMap(_ -> delayUnlessPaused(timeout, scheduler, paused)).next();
  }

  /**
   * A delay that does not expire while {@code paused} holds: if the period ends during a pause, a
   * new full period begins.
   *
   * @param timeout the delay
   * @param scheduler the scheduler that measures time
   * @param paused tells whether the clock must hold
   * @return a Mono that emits when the delay expires outside a pause
   */
  public static Mono<Long> delayUnlessPaused(
      final Duration timeout, final Scheduler scheduler, final BooleanSupplier paused) {
    return Mono.delay(timeout, scheduler)
        .filter(_ -> !paused.getAsBoolean())
        .repeatWhenEmpty(repeats -> repeats);
  }
}
