// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Attempts waiting for a free slot in their pool, served round-robin across runs. */
final class PoolQueue {

  /* default */ PoolQueue(final MeterRegistry meters) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ <T> Mono<T> acquire(
      final String pool,
      final String executionId,
      final Supplier<Optional<T>> tryTake,
      final Duration timeout) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ void wake(final String pool) {
    throw new UnsupportedOperationException("not implemented");
  }

  /* default */ int pending(final String pool) {
    throw new UnsupportedOperationException("not implemented");
  }
}
