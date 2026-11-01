// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for PreparedWorkflowCache. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class PreparedWorkflowCacheTest {
  /** Session 1 ID. */
  private static final String SESSION_1 = "s1";

  /** Session 2 ID. */
  private static final String SESSION_2 = "s2";

  /** Workflow 1 ID. */
  private static final String WORKFLOW_1 = "wf1";

  /** Workflow 2 ID. */
  private static final String WORKFLOW_2 = "wf2";

  /** The cache under test. */
  private PreparedWorkflowCache cache;

  private static PreparedWorkflow mockPrepared() {
    return new PreparedWorkflow(List.of(), Map.of(), Map.of(), Map.of(), List.of(), null);
  }

  @BeforeEach
  void setUp() {
    // TTL of 200ms for fast eviction testing; init() is intentionally not called — eviction is
    // triggered manually via evictExpired()
    cache = new PreparedWorkflowCache(200L);
  }

  @Test
  void sizeIsCapped_andReportedByGauge() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final PreparedWorkflowCache small = new PreparedWorkflowCache(60_000L, 2, meters);
    for (int i = 0; i < 50; i++) {
      small.put(SESSION_1, "wf-" + i, mockPrepared());
    }

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(meters.get("yukta.workflow.cache.size").gauge().value())
                    .isLessThanOrEqualTo(2.0));
    assertThat(meters.get("yukta.workflow.cache.size").gauge().value()).isLessThanOrEqualTo(2.0);
  }

  @Test
  void putAndGetReturnsPreparedWorkflow() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, prepared);
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).contains(prepared);
  }

  @Test
  void shouldReturnEmptyWhenKeyIsUnknown() {
    assertThat(cache.get("unknown", WORKFLOW_1)).isEmpty();
  }

  @Test
  void invalidateRemovesEntry() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, prepared);
    cache.invalidate(SESSION_1, WORKFLOW_1);
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
  }

  @Test
  void invalidateAllRemovesAllEntriesForSession() {
    cache.put(SESSION_1, WORKFLOW_1, mockPrepared());
    cache.put(SESSION_1, WORKFLOW_2, mockPrepared());
    cache.put(SESSION_2, WORKFLOW_1, mockPrepared());
    cache.invalidateAll(SESSION_1);
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
    assertThat(cache.get(SESSION_1, WORKFLOW_2)).isEmpty();
    assertThat(cache.get(SESSION_2, WORKFLOW_1)).isPresent();
  }

  @Test
  void entryExpiredAfterTtl() throws InterruptedException {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, prepared);
    // past the 200 ms TTL: evict until the entry is gone
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              cache.evictExpired();
              return cache.getStats().evictionCount() == 1; // get() would refresh the entry
            });
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
  }

  @Test
  void accessResetsLastAccessTimeAndSurvivesTtl() throws InterruptedException {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, prepared);
    // real TTL elapses
    Thread.sleep(100L); // nosem: yukta-test-thread-sleep
    cache.get(SESSION_1, WORKFLOW_1); // resets lastAccessTime
    // real TTL elapses; 250ms since put but only 150ms since last access
    Thread.sleep(150L); // nosem: yukta-test-thread-sleep
    cache.evictExpired();
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).contains(prepared);
  }

  @Test
  void invalidate_nonExistentKey_isNoOp() {
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
    cache.invalidate(SESSION_1, WORKFLOW_1); // must not throw
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
  }

  @Test
  void invalidateAll_unknownSession_isNoOp() {
    cache.put(SESSION_2, WORKFLOW_1, mockPrepared());
    cache.invalidateAll(SESSION_1); // must not throw; s2 must be untouched
    assertThat(cache.get(SESSION_2, WORKFLOW_1)).isPresent();
  }

  @Test
  void put_existingEntry_overwritesWithNewValue() {
    final PreparedWorkflow first = mockPrepared();
    final PreparedWorkflow second = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, first);
    cache.put(SESSION_1, WORKFLOW_1, second);
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).contains(second);
  }

  @Test
  void evictExpired_emptyCache_completesWithoutException() {
    cache.evictExpired(); // must not throw on empty cache
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).isEmpty();
  }

  @Test
  void evictExpired_freshEntry_isNotEvicted() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, prepared);
    cache.evictExpired(); // TTL has not elapsed yet
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).contains(prepared);
  }

  @Test
  void get_differentSessions_returnsCorrectEntry() {
    final PreparedWorkflow forSession1 = mockPrepared();
    final PreparedWorkflow forSession2 = mockPrepared();
    cache.put(SESSION_1, WORKFLOW_1, forSession1);
    cache.put(SESSION_2, WORKFLOW_1, forSession2);
    assertThat(cache.get(SESSION_1, WORKFLOW_1)).contains(forSession1);
    assertThat(cache.get(SESSION_2, WORKFLOW_1)).contains(forSession2);
  }

  @Test
  @DisplayName("handles concurrent put and get operations")
  void testConcurrentOperations() throws InterruptedException {
    final String sessionId = "session-123";
    final CountDownLatch latch = new CountDownLatch(10);
    final List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      final int index = i;
      futures.add(
          CompletableFuture.runAsync(
              () -> {
                cache.put(sessionId, "workflow-" + index, mockPrepared());
                cache.get(sessionId, "workflow-" + index);
                latch.countDown();
              }));
    }

    latch.await();
    for (final CompletableFuture<Void> future : futures) {
      future.join();
    }

    final var stats = cache.getStats();
    assertThat(stats.hitCount()).isGreaterThan(0);
  }

  @Test
  @DisplayName("stats correctly track hits and misses")
  void testStatsAccuracy() {
    cache.put(SESSION_1, "w1", mockPrepared());
    cache.get(SESSION_1, "w1");
    cache.get(SESSION_1, "w1");
    cache.get(SESSION_1, "missing1");
    cache.get(SESSION_1, "missing2");

    final var stats = cache.getStats();
    assertThat(stats.hitCount()).isEqualTo(2L);
    assertThat(stats.missCount()).isEqualTo(3L); // put() also counts as a miss via getIfPresent()
  }

  @Test
  @DisplayName("shutdown logs cache statistics")
  void testShutdown() {
    cache.put(SESSION_1, WORKFLOW_1, mockPrepared());
    cache.put(SESSION_1, WORKFLOW_2, mockPrepared());
    cache.get(SESSION_1, WORKFLOW_1);
    cache.get(SESSION_1, "missing");

    cache.shutdown();

    final var stats = cache.getStats();
    assertThat(stats.hitCount()).isGreaterThan(0);
    assertThat(stats.missCount()).isGreaterThan(0);
  }
}
