// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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

  /** Most prepared workflows kept; the least recently used go first. */
  private static final long MAX_ENTRIES = 1_000;

  /** The Caffeine cache for prepared workflows. */
  private final Cache<String, Entry> cache;

  /** Sessions ({@code id}) and workflows ({@code id\0workflow}) that are being deleted. */
  private final java.util.Set<String> deleting = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Spring-managed constructor.
   *
   * @param ttlMs TTL in milliseconds from application properties
   */
  @Autowired
  public PreparedWorkflowCache(@Value("${workflow.cache.ttl.ms:600000}") final long ttlMs) {
    this.cache =
        Caffeine.newBuilder()
            .expireAfterAccess(ttlMs, TimeUnit.MILLISECONDS)
            // ponytail: fixed cap; make it a setting if a server needs more
            .maximumSize(MAX_ENTRIES)
            .recordStats()
            .removalListener(
                (key, _, cause) ->
                    log.atDebug()
                        .addKeyValue("key", key)
                        .addKeyValue("cause", cause)
                        .log("Evicted compiled workflow"))
            .build();
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
   * Store a compiled workflow whose definition is not known; a start that checks the definition
   * will not use it.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param prepared the compiled workflow
   */
  public void put(
      final String sessionId, final String workflowId, final PreparedWorkflow prepared) {
    put(sessionId, workflowId, prepared, null);
  }

  /**
   * Store a compiled workflow in the cache.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param prepared the compiled workflow
   * @param definition the definition it was built from, or null if not known
   */
  public void put(
      final String sessionId,
      final String workflowId,
      final PreparedWorkflow prepared,
      @Nullable final WorkflowDefinition definition) {
    final String compositeKey = key(sessionId, workflowId);
    final boolean isReplacement = cache.getIfPresent(compositeKey) != null;
    cache.put(compositeKey, new Entry(definition, prepared));
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
        Optional.ofNullable(cache.getIfPresent(key(sessionId, workflowId))).map(Entry::prepared);
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
   * Retrieve a compiled workflow only if it was built from {@code definition}: a start that loaded
   * a newer definition while an apply was in progress must not run the older graph.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param definition the definition the caller is about to run
   * @return the cached workflow, or empty if absent, evicted or built from another definition
   */
  public Optional<PreparedWorkflow> get(
      final String sessionId, final String workflowId, final WorkflowDefinition definition) {
    return Optional.ofNullable(cache.getIfPresent(key(sessionId, workflowId)))
        .filter(entry -> definition.equals(entry.definition()))
        .map(Entry::prepared);
  }

  /**
   * Marks a session (or one of its workflows) as being deleted, so starts are refused until {@link
   * #endDelete}.
   *
   * @param sessionId the session
   * @param workflowId the workflow, or null for the whole session
   */
  public void beginDelete(final String sessionId, @Nullable final String workflowId) {
    deleting.add(workflowId == null ? sessionId : key(sessionId, workflowId));
  }

  /**
   * Ends {@link #beginDelete}.
   *
   * @param sessionId the session
   * @param workflowId the workflow, or null for the whole session
   */
  public void endDelete(final String sessionId, @Nullable final String workflowId) {
    deleting.remove(workflowId == null ? sessionId : key(sessionId, workflowId));
  }

  /**
   * Whether the workflow, or its session, is being deleted.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return true if a delete is in progress
   */
  public boolean isDeleting(final String sessionId, final String workflowId) {
    return deleting.contains(sessionId) || deleting.contains(key(sessionId, workflowId));
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

  /** A prepared workflow and the definition it was built from (null when not known). */
  private record Entry(@Nullable WorkflowDefinition definition, PreparedWorkflow prepared) {}
}
