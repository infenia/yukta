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
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreparedWorkflowCacheTest {

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
  void putAndGetReturnsPreparedWorkflow() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put("s1", "wf1", prepared);
    assertThat(cache.get("s1", "wf1")).contains(prepared);
  }

  @Test
  void getOnUnknownKeyReturnsEmpty() {
    assertThat(cache.get("unknown", "wf1")).isEmpty();
  }

  @Test
  void invalidateRemovesEntry() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put("s1", "wf1", prepared);
    cache.invalidate("s1", "wf1");
    assertThat(cache.get("s1", "wf1")).isEmpty();
  }

  @Test
  void invalidateAllRemovesAllEntriesForSession() {
    cache.put("s1", "wf1", mockPrepared());
    cache.put("s1", "wf2", mockPrepared());
    cache.put("s2", "wf1", mockPrepared());
    cache.invalidateAll("s1");
    assertThat(cache.get("s1", "wf1")).isEmpty();
    assertThat(cache.get("s1", "wf2")).isEmpty();
    assertThat(cache.get("s2", "wf1")).isPresent();
  }

  @Test
  void entryExpiredAfterTtl() throws InterruptedException {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put("s1", "wf1", prepared);
    Thread.sleep(300L); // past the 200ms TTL
    cache.evictExpired(); // trigger eviction manually
    assertThat(cache.get("s1", "wf1")).isEmpty();
  }

  @Test
  void accessResetsLastAccessTimeAndSurvivesTtl() throws InterruptedException {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put("s1", "wf1", prepared);
    Thread.sleep(100L);
    cache.get("s1", "wf1"); // resets lastAccessTime
    Thread.sleep(150L); // 250ms since put but only 150ms since last access
    cache.evictExpired();
    assertThat(cache.get("s1", "wf1")).contains(prepared);
  }

  @Test
  void invalidate_nonExistentKey_isNoOp() {
    assertThat(cache.get("s1", "wf1")).isEmpty();
    cache.invalidate("s1", "wf1"); // must not throw
    assertThat(cache.get("s1", "wf1")).isEmpty();
  }

  @Test
  void invalidateAll_unknownSession_isNoOp() {
    cache.put("s2", "wf1", mockPrepared());
    cache.invalidateAll("s1"); // must not throw; s2 must be untouched
    assertThat(cache.get("s2", "wf1")).isPresent();
  }

  @Test
  void put_existingEntry_overwritesWithNewValue() {
    final PreparedWorkflow first = mockPrepared();
    final PreparedWorkflow second = mockPrepared();
    cache.put("s1", "wf1", first);
    cache.put("s1", "wf1", second);
    assertThat(cache.get("s1", "wf1")).contains(second);
  }

  @Test
  void evictExpired_emptyCache_completesWithoutException() {
    cache.evictExpired(); // must not throw on empty cache
    assertThat(cache.get("s1", "wf1")).isEmpty();
  }

  @Test
  void evictExpired_freshEntry_isNotEvicted() {
    final PreparedWorkflow prepared = mockPrepared();
    cache.put("s1", "wf1", prepared);
    cache.evictExpired(); // TTL has not elapsed yet
    assertThat(cache.get("s1", "wf1")).contains(prepared);
  }

  @Test
  void get_differentSessions_returnsCorrectEntry() {
    final PreparedWorkflow forS1 = mockPrepared();
    final PreparedWorkflow forS2 = mockPrepared();
    cache.put("s1", "wf1", forS1);
    cache.put("s2", "wf1", forS2);
    assertThat(cache.get("s1", "wf1")).contains(forS1);
    assertThat(cache.get("s2", "wf1")).contains(forS2);
  }

  @Test
  @DisplayName("handles concurrent put and get operations")
  void testConcurrentOperations() throws InterruptedException {
    String sessionId = "session-123";
    CountDownLatch latch = new CountDownLatch(10);
    List<CompletableFuture<Void>> futures = new ArrayList<>();

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
    for (var future : futures) {
      future.join();
    }

    assertThat(cache.getStats().hitCount()).isGreaterThan(0);
  }

  @Test
  @DisplayName("stats correctly track hits and misses")
  void testStatsAccuracy() {
    cache.put("s1", "w1", mockPrepared());
    cache.get("s1", "w1");
    cache.get("s1", "w1");
    cache.get("s1", "missing1");
    cache.get("s1", "missing2");

    var stats = cache.getStats();
    assertThat(stats.hitCount()).isEqualTo(2L);
    assertThat(stats.missCount()).isEqualTo(3L); // put() also counts as a miss via getIfPresent()
  }
}
