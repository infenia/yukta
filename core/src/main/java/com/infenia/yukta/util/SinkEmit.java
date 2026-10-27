// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import lombok.experimental.UtilityClass;
import reactor.core.publisher.Sinks;

/**
 * Emits into a sink without ever terminating it.
 *
 * <p>{@code Sinks.Many#emitNext} ends the sink with an {@code OverflowException} when its buffer is
 * full, which silently stops every later emission. {@code tryEmitNext} only reports the failure, so
 * the caller decides what to do with the element.
 */
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn"})
public class SinkEmit {

  /** Busy retries before the thread starts to yield; keeps the common, brief clash cheap. */
  private static final int MAX_SPINS = 1_000;

  /**
   * How long a concurrent emission is retried before giving up. The other emitter finishes in
   * microseconds unless it is descheduled (a CPU-limited container), so a count of spins is too
   * short; the deadline also ends a reentrant emit from a subscriber, which can never succeed.
   */
  private static final long MAX_RETRY_NANOS = 250_000_000L;

  /**
   * Emits one element, retrying only while another thread is emitting at the same moment.
   *
   * @param sink the sink
   * @param value the element
   * @param <T> the element type
   * @return true if the sink accepted the element; false if it is full, terminated or has no
   *     subscriber that can take it
   */
  public <T> boolean offer(final Sinks.Many<T> sink, final T value) {
    return offer(sink, value, MAX_RETRY_NANOS);
  }

  /**
   * Emits one element like {@link #offer(Sinks.Many, Object)} with a retry deadline of the caller's
   * choice (a test needs one that a loaded machine cannot overrun).
   *
   * @param sink the sink
   * @param value the element
   * @param retryNanos how long a concurrent emission is retried
   * @param <T> the element type
   * @return true if the sink accepted the element
   */
  /* default */ <T> boolean offer(final Sinks.Many<T> sink, final T value, final long retryNanos) {
    final long deadline = System.nanoTime() + retryNanos;
    for (int attempt = 0; ; attempt++) {
      final Sinks.EmitResult result = sink.tryEmitNext(value);
      if (result == Sinks.EmitResult.OK) {
        return true;
      }
      if (result != Sinks.EmitResult.FAIL_NON_SERIALIZED || System.nanoTime() - deadline > 0) {
        return false;
      }
      if (attempt < MAX_SPINS) {
        Thread.onSpinWait();
      } else {
        Thread.yield();
      }
    }
  }
}
