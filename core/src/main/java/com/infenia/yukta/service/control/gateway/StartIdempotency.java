// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
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

  /** Execution id by scope and key; entries expire a fixed time after they were written. */
  private final Cache<String, Mono<String>> entries;

  /**
   * Creates the registry.
   *
   * @param ttl how long a key remembers its execution
   */
  @Autowired
  public StartIdempotency(@Value("${yukta.start.idempotency-ttl:10m}") final Duration ttl) {
    this(ttl, Ticker.systemTicker());
  }

  /* default */ StartIdempotency(final Duration ttl, final Ticker ticker) {
    this.entries = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
  }

  /**
   * Starts once per key.
   *
   * @param scope what the key is unique within (session and workflow)
   * @param key the client's idempotency key, or null/blank for none
   * @param start starts a new execution and returns its id
   * @return the execution id of the first start with this key within the TTL
   */
  public Mono<String> once(
      final String scope, final String key, final Supplier<Mono<String>> start) {
    if (key == null || key.isBlank()) {
      return start.get();
    }
    final String mapKey = scope + "\0" + key;
    final Mono<String> result = entries.get(mapKey, _ -> start.get().cache());
    // A failed start is not remembered, so the client can retry with the same key.
    return result.doOnError(_ -> entries.asMap().remove(mapKey, result));
  }
}
