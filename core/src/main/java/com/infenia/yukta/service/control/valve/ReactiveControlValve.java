/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control.valve;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Reactive flow control valve for pause/resume via backpressure.
 *
 * <p>Uses {@code delayUntil} to halt downstream items when paused. Fast path when flowing; blocks
 * when paused until explicit resume.
 *
 * <p>Supports step-through mode: when enabled, each element must be explicitly stepped via the
 * stepSink signal, allowing one element through at a time.
 */
@NoArgsConstructor
public final class ReactiveControlValve {

  /** Flag indicating whether the valve is paused. */
  private final AtomicBoolean paused = new AtomicBoolean(false);

  /** Flag indicating whether step mode is enabled. */
  private final AtomicBoolean stepMode = new AtomicBoolean(false);

  /** Sink for resume signals. */
  private final Many<Boolean> resumeSink = Sinks.many().replay().latestOrDefault(true);

  /** Sink for step signals. */
  private final Many<Long> stepSignal = Sinks.many().replay().latestOrDefault(0L);

  /** Counter for tracking steps in step mode. */
  private final AtomicLong stepCounter = new AtomicLong(0);

  /**
   * Checks if passage is allowed. Returns immediately if flowing; blocks if paused.
   *
   * <p>In step mode, waits for next step signal via stepSink rather than permanent resume.
   *
   * @return Mono that emits true when passage is allowed
   */
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Mono<Boolean> allowPassage() {
    if (!paused.get()) {
      return Mono.just(true);
    }

    if (stepMode.get()) {
      final long currentStep = stepCounter.get();
      return stepSignal.asFlux().filter(s -> s > currentStep).next().thenReturn(true);
    }

    return resumeSink.asFlux().filter(Boolean::booleanValue).next().thenReturn(true);
  }

  /** Pauses the valve, causing downstream to block until resumed. */
  public void pause() {
    paused.set(true);
    resumeSink.tryEmitNext(false);
  }

  /** Resumes the valve, unblocking downstream. */
  public void resume() {
    paused.set(false);
    resumeSink.tryEmitNext(true);
  }

  /**
   * Checks current pause state without blocking.
   *
   * @return true if paused, false if flowing
   */
  public boolean isPaused() {
    return paused.get();
  }

  /**
   * Enables step-through mode. Each element must be explicitly stepped via {@code step()}.
   *
   * <p>Automatically pauses the valve.
   */
  public void enableStepMode() {
    stepMode.set(true);
    paused.set(true);
  }

  /** Disables step-through mode and resumes normal pause/resume. */
  public void disableStepMode() {
    stepMode.set(false);
    resume();
  }

  /**
   * Signals the next step in step-through mode. Allows exactly one element through.
   *
   * @return true if step signal was emitted, false if step mode not active
   */
  @SuppressWarnings("PMD.OnlyOneReturn")
  public boolean step() {
    if (!stepMode.get()) {
      return false;
    }
    final long nextStep = stepCounter.incrementAndGet();
    stepSignal.tryEmitNext(nextStep);
    return true;
  }

  /**
   * Checks current step-mode state.
   *
   * @return true if step mode is active, false otherwise
   */
  public boolean isStepMode() {
    return stepMode.get();
  }

  /**
   * Applies this valve as a transform to a flux source.
   *
   * <p>Usage: {@code flux.transform(valve.asTransform())}
   *
   * @return a function suitable for use with {@code Flux.transform()}
   */
  public <T> java.util.function.Function<Flux<T>, Flux<T>> asTransform() {
    return source -> source.delayUntil(t -> allowPassage().then());
  }
}
