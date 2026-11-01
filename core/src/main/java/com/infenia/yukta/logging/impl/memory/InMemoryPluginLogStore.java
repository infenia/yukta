// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * In-memory implementation of PluginLogStore using Caffeine cache.
 *
 * <p>Stores logs per execution with automatic expiration based on configured retention period. Each
 * execution keeps its newest lines only. Thread-safe via Caffeine's internal synchronization.
 */
@Slf4j
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
public class InMemoryPluginLogStore implements PluginLogStore {

  /** Most lines kept for one execution. */
  private static final int MAX_ENTRIES_PER_EXECUTION = 10_000;

  /** Most characters of log text kept across all executions. */
  private static final long MAX_CHARS = 64L * 1024 * 1024;

  /** Characters of log text kept across all executions. */
  private final long maxChars;

  /** Characters held by all executions. */
  private final AtomicLong held = new AtomicLong();

  /** Caffeine cache storing log entries per execution ID. */
  private final Cache<String, Lines> cache;

  /** Retention duration extracted from config. */
  private final Duration retention;

  /** The lines of one execution and the characters they hold; guarded by its own lock. */
  private static final class Lines {
    /** The lines, oldest first. */
    private final Deque<PluginLogEntry> entries = new ArrayDeque<>();

    /** Characters of message text held. */
    private long chars;
  }

  /**
   * Constructs an in-memory store with Caffeine cache.
   *
   * <p>Cache is configured with expireAfterWrite based on effective retention period.
   *
   * @param config the log store configuration
   */
  public InMemoryPluginLogStore(final PluginLogStoreConfig config) {
    this(config, MAX_CHARS);
  }

  /* default */ InMemoryPluginLogStore(final PluginLogStoreConfig config, final long maxChars) {
    this.maxChars = maxChars;
    final Duration effectiveRetention = config.getEffectiveRetention();
    this.retention = effectiveRetention;

    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(effectiveRetention)
            .<String, Lines>removalListener(
                (key, lines, cause) -> {
                  synchronized (lines) {
                    held.addAndGet(-lines.chars);
                    lines.chars = 0;
                  }
                  log.debug("Log entry expired for execution: {} ({})", key, cause);
                })
            .build();
  }

  @Override
  public Mono<Void> write(final PluginLogEntry entry) {
    return Mono.fromRunnable(
            () -> {
              final String executionId = entry.executionId();
              // A plain deque under its own lock: a copy-on-write list copied itself on every line.
              final Lines lines = cache.asMap().computeIfAbsent(executionId, k -> new Lines());
              synchronized (lines) {
                // a chatty plugin keeps the newest lines; the oldest are dropped
                if (lines.entries.size() >= MAX_ENTRIES_PER_EXECUTION) {
                  drop(lines);
                }
                lines.entries.addLast(entry);
                lines.chars += entry.message().length();
                held.addAndGet(entry.message().length());
                // over the global cap, the execution being written to gives up its oldest lines
                while (held.get() > maxChars && lines.entries.size() > 1) {
                  drop(lines);
                }
              }
              log.atTrace()
                  .log(
                      "Log written for execution {}: [{}] {}",
                      executionId,
                      entry.logLevel(),
                      entry.message());
            })
        .then();
  }

  private void drop(final Lines lines) {
    final int size = lines.entries.removeFirst().message().length();
    lines.chars -= size;
    held.addAndGet(-size);
  }

  @Override
  public Flux<PluginLogEntry> readExecution(final String executionId) {
    final Lines lines = cache.getIfPresent(executionId);
    final List<PluginLogEntry> copy = new ArrayList<>();
    if (lines != null) {
      synchronized (lines) {
        copy.addAll(lines.entries);
      }
    }
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
