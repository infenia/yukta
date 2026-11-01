// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory cache of compiled {@link PreparedWorkflow} instances, keyed by sessionId + workflowId.
 *
 * <p>Entries expire after {@code workflow.cache.ttl.ms} milliseconds of inactivity. Caffeine
 * automatically evicts expired entries. Access resets the TTL.
 */
@Slf4j
@Component
public class PreparedWorkflowCache {

  /** Separator for composite key construction. */
  private static final String COMPOSITE_KEY_SEPARATOR = "\0";

  /** Property key for session ID. */
  private static final String SESSION_ID_KEY = "sessionId";

  /** Property key for workflow ID. */
  private static final String WORKFLOW_ID_KEY = "workflowId";

  /** Default for most prepared workflows kept; the least recently used go first. */
  private static final long DEFAULT_MAX_ENTRIES = 1_000;

  /** The Caffeine cache for prepared workflows. */
  private final Cache<String, PreparedWorkflow> cache;

  /**
   * Creates a cache with the default size limit and no shared meters.
   *
   * @param ttlMs TTL in milliseconds
   */
  public PreparedWorkflowCache(final long ttlMs) {
    this(ttlMs, DEFAULT_MAX_ENTRIES, new SimpleMeterRegistry());
  }

  /**
   * Spring-managed constructor.
   *
   * @param ttlMs TTL in milliseconds from application properties
   * @param maxEntries most workflows kept ({@code workflow.cache.max-size})
   * @param meters where the {@code yukta.workflow.cache.size} gauge is registered
   */
  @Autowired
  public PreparedWorkflowCache(
      @Value("${workflow.cache.ttl.ms:600000}") final long ttlMs,
      @Value("${workflow.cache.max-size:1000}") final long maxEntries,
      final MeterRegistry meters) {
    this.cache =
        Caffeine.newBuilder()
            .expireAfterAccess(ttlMs, TimeUnit.MILLISECONDS)
            .maximumSize(maxEntries)
            .recordStats()
            .removalListener(
                (key, _, cause) ->
                    log.atDebug()
                        .addKeyValue("key", key)
                        .addKeyValue("cause", cause)
                        .log("Evicted compiled workflow"))
            .build();
    Gauge.builder("yukta.workflow.cache.size", cache, Cache::estimatedSize)
        .description("Prepared workflows held in memory")
        .register(meters);
    log.atInfo().addKeyValue("ttlMs", ttlMs).log("Initialized PreparedWorkflowCache with Caffeine");
  }

  /** Shut down the cache and log statistics. */
  @PreDestroy
  public void shutdown() {
    final CacheStats stats = cache.stats();
    log.atInfo()
        .addKeyValue("cacheSize", cache.asMap().size())
        .addKeyValue("hitCount", stats.hitCount())
        .addKeyValue("missCount", stats.missCount())
        .addKeyValue("evictionCount", stats.evictionCount())
        .log("Shutting down PreparedWorkflowCache");
  }

  /**
   * Store a compiled workflow in the cache.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param prepared the compiled workflow
   */
  public void put(
      final String sessionId, final String workflowId, final PreparedWorkflow prepared) {
    final String compositeKey = key(sessionId, workflowId);
    final boolean isReplacement = cache.getIfPresent(compositeKey) != null;
    cache.put(compositeKey, prepared);
    log.atDebug()
        .addKeyValue(SESSION_ID_KEY, sessionId)
        .addKeyValue(WORKFLOW_ID_KEY, workflowId)
        .addKeyValue("isReplacement", isReplacement)
        .log("Cached compiled workflow");
  }

  /**
   * Retrieve a compiled workflow, resetting its TTL.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return the cached workflow, or empty if absent or evicted
   */
  public Optional<PreparedWorkflow> get(final String sessionId, final String workflowId) {
    final Optional<PreparedWorkflow> result =
        Optional.ofNullable(cache.getIfPresent(key(sessionId, workflowId)));
    if (result.isEmpty()) {
      log.atDebug()
          .addKeyValue(SESSION_ID_KEY, sessionId)
          .addKeyValue(WORKFLOW_ID_KEY, workflowId)
          .log("Cache miss: compiled workflow not found");
    } else {
      log.atDebug()
          .addKeyValue(SESSION_ID_KEY, sessionId)
          .addKeyValue(WORKFLOW_ID_KEY, workflowId)
          .log("Cache hit: retrieved compiled workflow");
    }
    return result;
  }

  /**
   * Remove a single entry from the cache.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   */
  public void invalidate(final String sessionId, final String workflowId) {
    final String compositeKey = key(sessionId, workflowId);
    cache.invalidate(compositeKey);
    log.atDebug()
        .addKeyValue(SESSION_ID_KEY, sessionId)
        .addKeyValue(WORKFLOW_ID_KEY, workflowId)
        .log("Invalidated compiled workflow cache entry");
  }

  /**
   * Remove all entries for a session from the cache.
   *
   * @param sessionId the session identifier
   */
  public void invalidateAll(final String sessionId) {
    final String prefix = sessionId + COMPOSITE_KEY_SEPARATOR;
    final int initialSize = cache.asMap().size();
    cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    final int removed = initialSize - cache.asMap().size();
    log.atInfo()
        .addKeyValue(SESSION_ID_KEY, sessionId)
        .addKeyValue("entriesRemoved", removed)
        .log("Invalidated all compiled workflows for session");
  }

  /**
   * Trigger pending maintenance tasks in Caffeine. With Caffeine's automatic eviction, this is
   * minimal but kept for backward compatibility with tests that call it.
   */
  public void evictExpired() {
    cache.cleanUp();
  }

  /**
   * Get cache statistics for observability.
   *
   * @return the cache statistics
   */
  public CacheStats getStats() {
    return cache.stats();
  }

  private static String key(final String sessionId, final String workflowId) {
    return sessionId + COMPOSITE_KEY_SEPARATOR + workflowId;
  }
}
