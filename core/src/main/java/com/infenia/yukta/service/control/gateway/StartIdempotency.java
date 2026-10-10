// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Makes workflow starts safe to retry.
 *
 * <p>A client that repeats a start with the same idempotency key within the time window gets the
 * execution id of the first start instead of a second execution. A start that failed is forgotten,
 * so retrying it runs again.
 */
@Component
@SuppressWarnings("PMD.OnlyOneReturn")
public class StartIdempotency {

  /** Remembered starts by scope and key. */
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /** How long a key is remembered. */
  private final Duration ttl;

  /** Source of time. */
  private final Clock clock;

  /**
   * Creates the component.
   *
   * @param ttl how long a key is remembered
   */
  @Autowired
  public StartIdempotency(@Value("${yukta.start.idempotency-ttl:10m}") final Duration ttl) {
    this(ttl, Clock.systemUTC());
  }

  /**
   * Creates the component with a given clock.
   *
   * @param ttl how long a key is remembered
   * @param clock source of time
   */
  /* default */ StartIdempotency(final Duration ttl, final Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * Runs {@code start} unless the same key was already used within the window.
   *
   * @param scope what the key is unique within, for example session and workflow
   * @param key the client's idempotency key, or null for a plain start
   * @param start starts the execution and emits its id
   * @return the execution id, shared by every caller that used the same key
   */
  public Mono<String> once(
      final String scope, final String key, final Supplier<Mono<String>> start) {
    if (key == null || key.isBlank()) {
      return start.get();
    }
    final Instant now = clock.instant();
    entries.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    final String mapKey = scope + "\0" + key;
    final Entry entry =
        entries.compute(
            mapKey,
            (_, existing) ->
                existing != null ? existing : new Entry(start.get().cache(), now.plus(ttl)));
    return entry.result().doOnError(_ -> entries.remove(mapKey, entry));
  }

  /** A remembered start. */
  private record Entry(Mono<String> result, Instant expiresAt) {}
}
