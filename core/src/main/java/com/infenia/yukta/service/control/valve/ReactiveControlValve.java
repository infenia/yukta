// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.valve;

import com.infenia.yukta.util.SinkEmit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Permit-based flow control for pause, resume and step-through.
 *
 * <p>The valve is in one of three modes:
 *
 * <ul>
 *   <li><b>flowing</b>: every element passes;
 *   <li><b>held</b>: no element passes until {@link #resume()};
 *   <li><b>stepping</b>: an element passes only against a credit; {@link #step()} adds one credit,
 *       and credits requested before an element arrives are kept.
 * </ul>
 *
 * <p>Place it in front of a node's input (see {@code ExecutionControl#applyPassageGate}), so that a
 * pause stops the node from pulling more work instead of merely hiding its output.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.OnlyOneReturn")
public final class ReactiveControlValve {

  /** Flow mode of the valve. */
  private enum Mode {
    FLOWING,
    HELD,
    STEPPING
  }

  /** Current mode. */
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.FLOWING);

  /** Credits available in stepping mode. */
  private final AtomicLong credits = new AtomicLong();

  /** Counts state changes; replayed so a late waiter re-checks the latest state. */
  private final AtomicLong version = new AtomicLong();

  /** Signals waiters that the mode or the credits changed. */
  private final Sinks.Many<Long> changes = Sinks.many().replay().latest();

  /**
   * Waits until one element may pass and takes its permit.
   *
   * @return a Mono that emits true when passage is granted
   */
  public Mono<Boolean> allowPassage() {
    return Mono.defer(
        () ->
            tryAcquire()
                ? Mono.just(true)
                : changes.asFlux().filter(_ -> tryAcquire()).next().thenReturn(true));
  }

  private boolean tryAcquire() {
    return switch (mode.get()) {
      case FLOWING -> true;
      case HELD -> false;
      case STEPPING -> takeCredit();
    };
  }

  private boolean takeCredit() {
    return credits.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
  }

  private void changed() {
    SinkEmit.offer(changes, version.incrementAndGet());
  }

  /** Holds the valve: no element passes until {@link #resume()}. */
  public void pause() {
    mode.set(Mode.HELD);
    changed();
  }

  /** Lets every element pass again and drops unused step credits. */
  public void resume() {
    mode.set(Mode.FLOWING);
    credits.set(0);
    changed();
  }

  /**
   * Whether elements are currently held back, including step mode.
   *
   * @return true unless the valve is flowing
   */
  public boolean isPaused() {
    return mode.get() != Mode.FLOWING;
  }

  /** Enters step mode with no credits: nothing passes until {@link #step()} is called. */
  public void enableStepMode() {
    credits.set(0);
    mode.set(Mode.STEPPING);
    changed();
  }

  /** Leaves step mode and lets every element pass. */
  public void disableStepMode() {
    resume();
  }

  /**
   * Adds one credit, letting exactly one more element pass. Credits are kept until used.
   *
   * @return true if the credit was added, false if the valve is not in step mode
   */
  public boolean step() {
    if (mode.get() != Mode.STEPPING) {
      return false;
    }
    credits.incrementAndGet();
    changed();
    return true;
  }

  /**
   * Whether the valve is in step mode.
   *
   * @return true while stepping
   */
  public boolean isStepMode() {
    return mode.get() == Mode.STEPPING;
  }
}
