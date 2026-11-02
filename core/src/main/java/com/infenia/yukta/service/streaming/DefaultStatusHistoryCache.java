// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.streaming;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.execution.WorkflowProgress;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Caffeine-backed in-memory cache for storing workflow progress updates per execution.
 *
 * <p>Uses a concurrent deque per execution to maintain insertion-order history of progress updates.
 * Each execution's history expires after a configurable TTL, with a hard maximum of 30 minutes. A
 * run keeps at most {@code max-per-run} snapshots (the oldest go first, so the newest, which is the
 * terminal one once the run ends, is always kept) and at most {@code max-executions} runs are
 * remembered.
 */
@Service
@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public class DefaultStatusHistoryCache implements StatusHistoryCache {

  /** Maximum TTL for history cache. */
  private static final int MAX_TTL_MINUTES = 30;

  /** Snapshots kept per execution. */
  private final int maxPerRun;

  /** Cache storing workflow progress history per execution. */
  private final Cache<String, Deque<WorkflowProgress>> cache;

  /**
   * Create a new cache with the configured TTL.
   *
   * @param ttlMinutes the time-to-live in minutes (must be > 0 and <= 30)
   */
  public DefaultStatusHistoryCache(final int ttlMinutes) {
    this(ttlMinutes, 200, 1000);
  }

  /**
   * Create a cache from the settings.
   *
   * @param properties the {@code yukta.streaming} settings
   */
  @Autowired
  public DefaultStatusHistoryCache(final YuktaProperties properties) {
    this(
        properties.streaming().statusHistoryTtlMinutes(),
        properties.streaming().statusHistoryMaxPerRun(),
        properties.streaming().statusHistoryMaxExecutions());
  }

  /**
   * Create a new cache with the configured TTL and bounds.
   *
   * @param ttlMinutes the time-to-live in minutes (must be > 0 and <= 30)
   * @param maxPerRun snapshots kept per execution (at least 1)
   * @param maxExecutions executions remembered (at least 1)
   */
  public DefaultStatusHistoryCache(
      final int ttlMinutes, final int maxPerRun, final int maxExecutions) {
    if (ttlMinutes <= 0) {
      throw new IllegalArgumentException("TTL must be greater than 0 minutes");
    }
    if (ttlMinutes > MAX_TTL_MINUTES) {
      throw new IllegalArgumentException(
          "TTL cannot exceed " + MAX_TTL_MINUTES + " minutes (got " + ttlMinutes + ")");
    }

    log.info("Initializing DefaultStatusHistoryCache with TTL={} minutes", ttlMinutes);

    this.maxPerRun = Math.max(1, maxPerRun);
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
            .maximumSize(Math.max(1, maxExecutions))
            .build();
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public void put(@NotBlank final String executionId, @NotNull final WorkflowProgress progress) {
    try {
      final Deque<WorkflowProgress> deque =
          cache.get(
              executionId,
              _ -> {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Creating new history deque");
                return new ConcurrentLinkedDeque<>();
              });

      deque.addLast(progress);
      // Bounded: the oldest go first, so the newest (the terminal one) is always kept. Size is
      // read once per put; the deque is at most maxPerRun long, so this is cheap.
      while (deque.size() > maxPerRun) {
        deque.pollFirst();
      }

      log.atTrace().addKeyValue("executionId", executionId).log("Progress recorded to history");
    } catch (final RuntimeException e) {
      log.atWarn()
          .setCause(e)
          .addKeyValue("executionId", executionId)
          .log("Failed to record progress to history");
    }
  }

  @Override
  public List<WorkflowProgress> get(@NotBlank final String executionId) {
    final Deque<WorkflowProgress> deque = cache.getIfPresent(executionId);

    final List<WorkflowProgress> history;
    if (deque == null) {
      log.atTrace().addKeyValue("executionId", executionId).log("History cache miss or expired");
      history = Collections.emptyList();
    } else {
      history = List.copyOf(deque);
      log.atTrace()
          .addKeyValue("executionId", executionId)
          .addKeyValue("historySize", history.size())
          .log("Retrieved history from cache");
    }

    return history;
  }
}
