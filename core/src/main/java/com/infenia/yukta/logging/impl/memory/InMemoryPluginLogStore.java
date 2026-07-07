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
package com.infenia.yukta.logging.impl.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory implementation of PluginLogStore using Caffeine cache.
 *
 * <p>Stores logs per execution with automatic expiration based on configured retention period. All
 * write operations are scheduled on boundedElastic to avoid blocking. Thread-safe via Caffeine's
 * internal synchronization.
 */
@Slf4j
public class InMemoryPluginLogStore implements PluginLogStore {

  /** Caffeine cache storing log entries per execution ID. */
  private final Cache<String, List<PluginLogEntry>> cache;

  /** Retention duration extracted from config. */
  private final Duration retention;

  /**
   * Constructs an in-memory store with Caffeine cache.
   *
   * <p>Cache is configured with expireAfterWrite based on effective retention period.
   *
   * @param config the log store configuration
   */
  public InMemoryPluginLogStore(final PluginLogStoreConfig config) {
    final Duration effectiveRetention = config.getEffectiveRetention();
    this.retention = effectiveRetention;

    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(effectiveRetention)
            .removalListener(
                (key, value, cause) ->
                    log.debug("Log entry expired for execution: {} ({})", key, cause))
            .build();
  }

  @Override
  public Mono<Void> write(final PluginLogEntry entry) {
    return Mono.fromRunnable(
            () -> {
              final String executionId = entry.executionId();
              cache
                  .asMap()
                  .computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>())
                  .add(entry);
              log.atTrace()
                  .log(
                      "Log written for execution {}: [{}] {}",
                      executionId,
                      entry.logLevel(),
                      entry.message());
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Flux<PluginLogEntry> readExecution(final String executionId) {
    final List<PluginLogEntry> entries = cache.getIfPresent(executionId);
    final List<PluginLogEntry> copy =
        entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    return Flux.fromIterable(copy).subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<Void> cleanup(final String executionId) {
    return Mono.fromRunnable(
            () -> {
              cache.invalidate(executionId);
              log.debug("Log store cleaned up for execution: {}", executionId);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Override
  public Duration getEffectiveRetention() {
    return retention;
  }
}
