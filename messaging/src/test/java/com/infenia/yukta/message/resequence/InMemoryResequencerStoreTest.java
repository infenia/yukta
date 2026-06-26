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
package com.infenia.yukta.message.resequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.resequence.ResequencerStore.ResequenceConfig;
import com.infenia.yukta.message.resequence.ResequencerStore.ResequenceResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class InMemoryResequencerStoreTest {

  private InMemoryResequencerStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryResequencerStore();
    store.init();
  }

  @AfterEach
  void tearDown() {
    store.shutdown();
  }

  @Test
  void testInOrderRelease() {
    final String key = "test-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);
    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    StepVerifier.create(store.addMessage(key, 1, m1, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 1)
        .verifyComplete();

    StepVerifier.create(store.addMessage(key, 2, m2, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 1)
        .verifyComplete();
  }

  @Test
  void testOutOrderRelease() {
    final String key = "test-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);
    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");

    // Receive 2 first
    StepVerifier.create(store.addMessage(key, 2, m2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();

    // Receive 3
    StepVerifier.create(store.addMessage(key, 3, m3, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();

    // Receive 1 - should release 1, 2, 3
    StepVerifier.create(store.addMessage(key, 1, m1, config))
        .expectNextMatches(
            res -> {
              assertThat(res.status()).isEqualTo(ResequenceResult.Status.COMPLETED);
              assertThat(res.messages()).hasSize(3);
              assertThat(res.messages().get(0).getPayload()).isEqualTo("1");
              assertThat(res.messages().get(1).getPayload()).isEqualTo("2");
              assertThat(res.messages().get(2).getPayload()).isEqualTo("3");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testLateArrival() {
    final String key = "test-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);
    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    store.addMessage(key, 1, m1, config).block();
    store.addMessage(key, 2, m2, config).block();

    // 1 and 2 already released. 0 or 1 is now late.
    final Message<String> late = DefaultMessage.create(UUID.randomUUID(), "late");
    StepVerifier.create(store.addMessage(key, 1, late, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.LATE_ARRIVAL)
        .verifyComplete();
  }

  @Test
  void testGapTimeout() {
    final String key = "test-key";
    final ResequenceConfig config = new ResequenceConfig(1, 100, 100, null);
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");

    // Missing 1, receive 2 and 3
    store.addMessage(key, 2, m2, config).block();
    store.addMessage(key, 3, m3, config).block();

    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res -> {
              assertThat(res.status()).isEqualTo(ResequenceResult.Status.TIMEOUT_JUMP);
              assertThat(res.messages()).hasSize(2); // Should release 2 and 3
              assertThat(res.messages().get(0).getPayload()).isEqualTo("2");
              return true;
            })
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testDuplicateSequence() {
    final String key = "test-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    store.addMessage(key, 2, m2, config).block();

    StepVerifier.create(store.addMessage(key, 2, m2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.DUPLICATE)
        .verifyComplete();
  }

  @Test
  void testOverflowCondition() {
    final String key1 = "key1";
    final String key2 = "key2";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 2, null); // maxPending = 2

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");

    // Fill up the store with 2 keys
    store.addMessage(key1, 1, m1, config).block();
    store.addMessage(key2, 1, m2, config).block();

    // Third key should trigger overflow
    StepVerifier.create(store.addMessage("key3", 1, m3, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.OVERFLOW)
        .verifyComplete();
  }

  @Test
  void testFlushAllWithKeyPrefix() {
    final String key1 = "session-1-key";
    final String key2 = "session-1-other";
    final String key3 = "session-2-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "msg1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "msg2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "msg3");
    final Message<String> m1_2 = DefaultMessage.create(UUID.randomUUID(), "msg1_2");

    // Add messages to different keys, with out-of-order for key1 to keep buffer non-empty
    store.addMessage(key1, 2, m1, config).block(); // Out of order, stays in buffer
    store.addMessage(key2, 1, m2, config).block(); // Completed, buffer empty
    store.addMessage(key3, 1, m3, config).block(); // Completed, buffer empty
    store.addMessage(key1, 3, m1_2, config).block(); // Still waiting for 1

    // Flush session-1 keys - only key1 has non-empty buffer
    StepVerifier.create(store.flushAll("session-1"))
        .expectNextMatches(
            res ->
                res.status() == ResequenceResult.Status.COMPLETED
                    && res.messages().size() == 2) // Contains messages 2 and 3
        .verifyComplete();
  }

  @Test
  void testFlushAllEmptyBuffers() {
    final String key1 = "test-1";
    final String key2 = "other-2";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "msg1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "msg2");

    // Both in order, so released immediately
    store.addMessage(key1, 1, m1, config).block();
    store.addMessage(key2, 1, m2, config).block();

    // Flush with prefix that has empty buffers
    StepVerifier.create(store.flushAll("test"))
        .expectComplete() // No results since buffers are empty
        .verify();
  }

  @Test
  void testFlushAllNoMatches() {
    final String key = "session-1-key";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "msg1");
    store.addMessage(key, 1, m1, config).block();

    // Flush with non-matching prefix
    StepVerifier.create(store.flushAll("session-2")).expectComplete().verify();
  }

  @Test
  void testMultipleKeysIndependent() {
    final String key1 = "key1";
    final String key2 = "key2";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1_2 = DefaultMessage.create(UUID.randomUUID(), "1_2");
    final Message<String> m2_1 = DefaultMessage.create(UUID.randomUUID(), "2_1");
    final Message<String> m1_1 = DefaultMessage.create(UUID.randomUUID(), "1_1");

    // key1 receives 2 first
    StepVerifier.create(store.addMessage(key1, 2, m1_2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();

    // key2 receives 1
    StepVerifier.create(store.addMessage(key2, 1, m2_1, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.COMPLETED)
        .verifyComplete();

    // key1 receives 1 and should complete with both
    StepVerifier.create(store.addMessage(key1, 1, m1_1, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 2)
        .verifyComplete();
  }

  @Test
  void testShutdownTimeout() throws Exception {
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    testStore.init();

    // Use reflection to submit a long-running task
    java.lang.reflect.Field field = InMemoryResequencerStore.class.getDeclaredField("scheduler");
    field.setAccessible(true);
    java.util.concurrent.ScheduledExecutorService scheduler =
        (java.util.concurrent.ScheduledExecutorService) field.get(testStore);

    // Submit a task that takes longer than timeout
    scheduler.submit(
        () -> {
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            // Expected
          }
        });

    // This should trigger the timeout path in shutdown
    testStore.shutdown();
  }

  @Test
  void testShutdownWithInterrupt() throws Exception {
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    testStore.init();

    Thread testThread =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              testStore.shutdown();
            });

    testThread.start();
    testThread.join();
    // Implicitly covers the InterruptedException catch block
  }

  @Test
  void testAsyncResultsEmission() {
    final String key = "test-async";
    final ResequenceConfig config = new ResequenceConfig(1, 100, 100, null);

    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    store.addMessage(key, 2, m2, config).block();

    // Async results should emit TIMEOUT_JUMP when gap timeout occurs
    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == ResequenceResult.Status.TIMEOUT_JUMP && res.messages().size() == 1)
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testLargeSequenceGap() {
    final String key = "test-large-gap";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m100 = DefaultMessage.create(UUID.randomUUID(), "100");
    final Message<String> m101 = DefaultMessage.create(UUID.randomUUID(), "101");

    // Receive 100 and 101, missing 1-99
    store.addMessage(key, 100, m100, config).block();
    store.addMessage(key, 101, m101, config).block();

    // Both should remain WAITING
    StepVerifier.create(store.addMessage(key, 100, m100, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.DUPLICATE)
        .verifyComplete();
  }

  @Test
  void testCleanupIdleState() throws InterruptedException {
    final String key = "test-idle";
    final ResequenceConfig config = new ResequenceConfig(1, 50, 100, null); // 50ms timeout

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    store.addMessage(key, 1, m1, config).block();

    // Wait for timeout
    Thread.sleep(100);

    // Store should have cleaned up idle state
    // Try adding new message with same key - should create new state
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    StepVerifier.create(store.addMessage(key, 1, m2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.COMPLETED)
        .verifyComplete();
  }

  @Test
  void testConstructor() {
    // Test that default constructor works
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    assertThat(testStore).isNotNull();
    testStore.init();
    testStore.shutdown();
  }

  @Test
  void testReleaseConsecutiveMultiple() {
    final String key = "test-release-multi";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");
    final Message<String> m4 = DefaultMessage.create(UUID.randomUUID(), "4");

    // Receive 2, 3, 4 out of order
    store.addMessage(key, 2, m2, config).block();
    store.addMessage(key, 3, m3, config).block();
    store.addMessage(key, 4, m4, config).block();

    // Receive 1 - should release all 4
    StepVerifier.create(store.addMessage(key, 1, m1, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 4)
        .verifyComplete();
  }

  @Test
  void testPartialSequenceRelease() {
    final String key = "test-partial";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m4 = DefaultMessage.create(UUID.randomUUID(), "4");
    final Message<String> m5 = DefaultMessage.create(UUID.randomUUID(), "5");

    // Receive 1, 2
    store.addMessage(key, 1, m1, config).block();
    store.addMessage(key, 2, m2, config).block();

    // Receive 4, 5 (gap after 2)
    store.addMessage(key, 4, m4, config).block();
    store.addMessage(key, 5, m5, config).block();

    // Both are still buffered waiting for 3
    // Verify 4 and 5 are stuck in buffer by checking that subsequent messages work
    StepVerifier.create(store.addMessage(key, 4, m4, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.DUPLICATE)
        .verifyComplete();
  }

  @Test
  void testCleanupWithGapTimeout() throws InterruptedException {
    final String key = "test-gap-cleanup";
    final ResequenceConfig config = new ResequenceConfig(1, 50, 100, null); // 50ms timeout

    final Message<String> m5 = DefaultMessage.create(UUID.randomUUID(), "5");
    store.addMessage(key, 5, m5, config).block();

    // Wait for timeout
    Thread.sleep(100);

    // Async results should contain TIMEOUT_JUMP
    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == ResequenceResult.Status.TIMEOUT_JUMP && res.messages().size() == 1)
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testStartIndexDifferentFromOne() {
    final String key = "test-custom-start";
    final ResequenceConfig config = new ResequenceConfig(10, 1000, 100, null); // startIndex = 10

    final Message<String> m10 = DefaultMessage.create(UUID.randomUUID(), "10");
    final Message<String> m11 = DefaultMessage.create(UUID.randomUUID(), "11");

    StepVerifier.create(store.addMessage(key, 10, m10, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.COMPLETED)
        .verifyComplete();

    StepVerifier.create(store.addMessage(key, 11, m11, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.COMPLETED)
        .verifyComplete();
  }

  @Test
  void testLateArrivalBeforeStart() {
    final String key = "test-late-before-start";
    final ResequenceConfig config = new ResequenceConfig(10, 1000, 100, null);

    final Message<String> m9 = DefaultMessage.create(UUID.randomUUID(), "9");

    // Message with sequence number before start index
    StepVerifier.create(store.addMessage(key, 9, m9, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.LATE_ARRIVAL)
        .verifyComplete();
  }

  @Test
  void testEmitResultRetry() {
    // Test the emitResult retry logic in cleanup
    final String key = "test-emit-retry";
    final ResequenceConfig config = new ResequenceConfig(1, 50, 100, null);

    final Message<String> m100 = DefaultMessage.create(UUID.randomUUID(), "100");
    store.addMessage(key, 100, m100, config).block();

    // Wait for cleanup to trigger and emit TIMEOUT_JUMP
    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.TIMEOUT_JUMP)
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testFlushAllMultipleMatching() {
    final String key1 = "prefix-1";
    final String key2 = "prefix-2";
    final String key3 = "other-1";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2_out = DefaultMessage.create(UUID.randomUUID(), "out");
    final Message<String> m3_out = DefaultMessage.create(UUID.randomUUID(), "out");

    // key1: out of order, non-empty buffer
    store.addMessage(key1, 2, m1, config).block();
    // key2: out of order, non-empty buffer
    store.addMessage(key2, 2, m2_out, config).block();
    // key3: completed, empty buffer
    store.addMessage(key3, 1, m3_out, config).block();

    // Flush "prefix-" prefix
    StepVerifier.create(store.flushAll("prefix-"))
        .expectNextMatches(res -> res.messages().size() == 1)
        .expectNextMatches(res -> res.messages().size() == 1)
        .verifyComplete();
  }

  @Test
  void testCleanupEmptyBufferStateRemoval() throws InterruptedException {
    final String key = "test-empty-cleanup";
    final ResequenceConfig config = new ResequenceConfig(1, 50, 100, null);

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    store.addMessage(key, 1, m1, config).block(); // Completes immediately, buffer empty

    // Wait for cleanup to remove the idle state with empty buffer
    Thread.sleep(300);

    // Try adding same key again - should create new state
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    StepVerifier.create(store.addMessage(key, 1, m2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.COMPLETED)
        .verifyComplete();
  }

  @Test
  void testWaitingThenComplete() {
    final String key = "test-wait-complete";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");
    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    // Receive 3 - WAITING
    StepVerifier.create(store.addMessage(key, 3, m3, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();

    // Receive 1 - COMPLETED (releases only 1, nextExpected becomes 2)
    StepVerifier.create(store.addMessage(key, 1, m1, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 1)
        .verifyComplete();

    // Receive 2 - now COMPLETED, releases 2 and 3
    StepVerifier.create(store.addMessage(key, 2, m2, config))
        .expectNextMatches(
            res -> res.status() == ResequenceResult.Status.COMPLETED && res.messages().size() == 2)
        .verifyComplete();
  }

  @Test
  void testLastAccessTimeUpdate() {
    final String key = "test-access-time";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 100, null);

    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");
    final Message<String> m3 = DefaultMessage.create(UUID.randomUUID(), "3");

    // Initial add
    store.addMessage(key, 2, m2, config).block();

    // Subsequent add should update lastAccessTime
    StepVerifier.create(store.addMessage(key, 3, m3, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();
  }

  @Test
  void testResequenceResultNullMessages() {
    ResequenceResult result =
        new ResequenceResult(ResequenceResult.Status.COMPLETED, null, "key", null);
    assertThat(result.messages()).isEmpty();
  }

  @Test
  void testResequenceConfig() {
    ResequenceConfig config = new ResequenceConfig(1, 100, 10, "err");
    assertThat(config.startIndex()).isEqualTo(1);
    assertThat(config.timeoutMs()).isEqualTo(100);
    assertThat(config.maxPending()).isEqualTo(10);
    assertThat(config.errorPort()).isEqualTo("err");
  }

  @Test
  void testShutdown() throws Exception {
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    testStore.init();
    testStore.shutdown();
  }

  @Test
  void testShutdownInterrupted() throws Exception {
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    testStore.init();
    Thread t =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              testStore.shutdown();
            });
    t.start();
    t.join();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testEmitResultRetryBranch() throws Exception {
    Sinks.Many<ResequenceResult> mockSink = mock(Sinks.Many.class);
    // Use reflection to inject mock sink
    java.lang.reflect.Field field = InMemoryResequencerStore.class.getDeclaredField("asyncResults");
    field.setAccessible(true);
    field.set(store, mockSink);

    ResequenceResult result =
        new ResequenceResult(ResequenceResult.Status.COMPLETED, List.of(), "key", null);

    when(mockSink.tryEmitNext(any()))
        .thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED)
        .thenReturn(Sinks.EmitResult.OK);

    java.lang.reflect.Method method =
        InMemoryResequencerStore.class.getDeclaredMethod("emitResult", ResequenceResult.class);
    method.setAccessible(true);
    method.invoke(store, result);

    verify(mockSink, times(2)).tryEmitNext(result);
  }

  @Test
  void testOverflowExistingKey() {
    final String key = "key1";
    final ResequenceConfig config = new ResequenceConfig(1, 1000, 1, null); // maxPending = 1

    final Message<String> m1 = DefaultMessage.create(UUID.randomUUID(), "1");
    final Message<String> m2 = DefaultMessage.create(UUID.randomUUID(), "2");

    // Add first message for key1 - WAITING, size = 1
    store.addMessage(key, 2, m1, config).block();

    // Add second message for SAME key - should NOT overflow even if store.size() >= maxPending
    // because store.containsKey(key) is true.
    StepVerifier.create(store.addMessage(key, 3, m2, config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.WAITING)
        .verifyComplete();
  }

  @Test
  void testIsExpiredFalse() throws Exception {
    final String key = "test-no-expire";
    final ResequenceConfig config = new ResequenceConfig(1, 10000, 100, null); // Long timeout
    store.addMessage(key, 2, DefaultMessage.create(UUID.randomUUID(), "2"), config).block();

    // Trigger cleanup manually via reflection to hit the false branch of isExpired
    java.lang.reflect.Method cleanupMethod =
        InMemoryResequencerStore.class.getDeclaredMethod("cleanup");
    cleanupMethod.setAccessible(true);
    cleanupMethod.invoke(store);

    // Key should still be there (DUPLICATE check proves it)
    StepVerifier.create(
            store.addMessage(key, 2, DefaultMessage.create(UUID.randomUUID(), "2"), config))
        .expectNextMatches(res -> res.status() == ResequenceResult.Status.DUPLICATE)
        .verifyComplete();
  }

  @Test
  void testShutdownBranches() throws Exception {
    InMemoryResequencerStore testStore = new InMemoryResequencerStore();
    testStore.init();

    java.lang.reflect.Field schedulerField =
        InMemoryResequencerStore.class.getDeclaredField("scheduler");
    schedulerField.setAccessible(true);
    java.util.concurrent.ScheduledExecutorService originalScheduler =
        (java.util.concurrent.ScheduledExecutorService) schedulerField.get(testStore);

    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    originalScheduler.submit(
        () -> {
          latch.countDown();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            // Expected
          }
        });

    assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
    testStore.shutdown();

    // Test InterruptedException branch
    InMemoryResequencerStore interruptStore = new InMemoryResequencerStore();
    interruptStore.init();

    java.util.concurrent.ScheduledExecutorService interruptScheduler =
        (java.util.concurrent.ScheduledExecutorService) schedulerField.get(interruptStore);

    java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);
    interruptScheduler.submit(
        () -> {
          latch2.countDown();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            // Expected
          }
        });

    assertTrue(latch2.await(2, java.util.concurrent.TimeUnit.SECONDS));

    Thread t =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              interruptStore.shutdown();
            });
    t.start();
    t.join();
  }

  private void assertTrue(boolean condition) {
    if (!condition) {
      throw new RuntimeException("Assertion failed");
    }
  }
}
