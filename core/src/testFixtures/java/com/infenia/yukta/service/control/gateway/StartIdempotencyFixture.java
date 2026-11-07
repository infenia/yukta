// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.github.benmanes.caffeine.cache.Ticker;
import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import java.time.Duration;

/**
 * Short forms of {@link StartIdempotency} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class StartIdempotencyFixture {

  private StartIdempotencyFixture() {}

  /** A StartIdempotency with the default for what the parameters leave out. */
  public static StartIdempotency create(
      final YuktaProperties properties, final ExecutionLogStore log) {
    return new StartIdempotency(properties, log);
  }

  /** A StartIdempotency with the default for what the parameters leave out. */
  public static StartIdempotency create(
      final Duration ttl, final ExecutionLogStore log, final long maxKeys) {
    return new StartIdempotency(ttl, Ticker.systemTicker(), log, maxKeys);
  }

  /** A StartIdempotency with the default for what the parameters leave out. */
  public static StartIdempotency create(final Duration ttl, final ExecutionLogStore log) {
    return new StartIdempotency(ttl, Ticker.systemTicker(), log, 100_000);
  }

  /** A StartIdempotency with the default for what the parameters leave out. */
  public static StartIdempotency create(final Duration ttl) {
    return new StartIdempotency(
        ttl, Ticker.systemTicker(), new InMemoryExecutionLogStore(), 100_000);
  }

  /** A StartIdempotency with the default for what the parameters leave out. */
  public static StartIdempotency create(final Duration ttl, final Ticker ticker) {
    return new StartIdempotency(ttl, ticker, new InMemoryExecutionLogStore(), 100_000);
  }
}
