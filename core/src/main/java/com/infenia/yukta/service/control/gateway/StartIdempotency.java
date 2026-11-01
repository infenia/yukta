// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
public class StartIdempotency implements SmartInitializingSingleton {

  /** Execution id by scope and key; entries expire a fixed time after they were written. */
  private final Cache<String, Mono<String>> entries;

  /** Where keys are written and read back after a restart; null keeps them in memory only. */
  @Nullable private final ExecutionLogStore log;

  /** How long a key remembers its execution. */
  private final Duration ttl;

  /** Most keys remembered; beyond it the least recently used one is forgotten early. */
  private static final long DEFAULT_MAX_KEYS = 100_000;

  /**
   * Creates the registry.
   *
   * @param ttl how long a key remembers its execution
   * @param log the execution log, which keeps keys across a restart when it is on disk
   * @param maxKeys most keys remembered; a key evicted before its TTL lets a repeat start again
   */
  @Autowired
  public StartIdempotency(
      @Value("${yukta.start.idempotency-ttl:10m}") final Duration ttl,
      final ExecutionLogStore log,
      @Value("${yukta.start.idempotency-max-keys:100000}") final long maxKeys) {
    this(ttl, Ticker.systemTicker(), log, maxKeys);
  }

  /**
   * Creates a registry with the default key limit.
   *
   * @param ttl how long a key remembers its execution
   * @param log the execution log, which keeps keys across a restart when it is on disk
   */
  public StartIdempotency(final Duration ttl, final ExecutionLogStore log) {
    this(ttl, Ticker.systemTicker(), log, DEFAULT_MAX_KEYS);
  }

  /**
   * Creates a registry that keeps its keys in memory only.
   *
   * @param ttl how long a key remembers its execution
   */
  public StartIdempotency(final Duration ttl) {
    this(ttl, Ticker.systemTicker(), null, DEFAULT_MAX_KEYS);
  }

  /* default */ StartIdempotency(final Duration ttl, final Ticker ticker) {
    this(ttl, ticker, null, DEFAULT_MAX_KEYS);
  }

  /* default */ StartIdempotency(
      final Duration ttl,
      final Ticker ticker,
      @Nullable final ExecutionLogStore log,
      final long maxKeys) {
    this.ttl = ttl;
    this.log = log;
    this.entries =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(Math.max(1, maxKeys))
            .executor(Runnable::run)
            .ticker(ticker)
            .build();
  }

  /** Reloads the keys written before the last restart that have not expired yet. */
  @Override
  public void afterSingletonsInstantiated() {
    if (log == null) {
      return;
    }
    final Instant oldest = Instant.now().minus(ttl);
    for (final ExecutionLogEvent event :
        log.ofType(ExecutionLogEvent.Type.IDEMPOTENCY_KEY, oldest)) {
      entries.put(event.status(), Mono.just(event.executionId()));
    }
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
    return once(scope, key, start, _ -> true);
  }

  /**
   * Starts once per key while the remembered execution is still wanted.
   *
   * @param scope what the key is unique within (session and workflow)
   * @param key the client's idempotency key, or null/blank for none
   * @param start starts a new execution and returns its id
   * @param reusable whether the remembered execution still counts as the answer; when it does not
   *     (for example the restarted run already finished), the key starts a fresh execution
   * @return the remembered execution id, or the id of a new start
   */
  public Mono<String> once(
      final String scope,
      final String key,
      final Supplier<Mono<String>> start,
      final Predicate<String> reusable) {
    if (key == null || key.isBlank()) {
      return start.get();
    }
    final String mapKey = scope + "\0" + key;
    final Mono<String> remembered = entries.getIfPresent(mapKey);
    if (remembered != null) {
      // Only a remembered id is vetted; the run this call starts is not registered yet.
      return remembered
          .flatMap(
              id -> {
                if (reusable.test(id)) {
                  return Mono.just(id);
                }
                entries.asMap().remove(mapKey, remembered);
                return once(scope, key, start, reusable);
              })
          .doOnError(_ -> entries.asMap().remove(mapKey, remembered));
    }
    final Mono<String> result =
        entries.get(
            mapKey,
            _ ->
                start
                    .get()
                    .doOnNext(
                        executionId -> {
                          if (log != null) {
                            log.append(ExecutionLogEvent.idempotencyKey(executionId, mapKey));
                          }
                        })
                    .cache());
    // A failed start is not remembered, so the client can retry with the same key.
    return result.doOnError(_ -> entries.asMap().remove(mapKey, result));
  }
}
