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
package com.infenia.yukta.message.aggregate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.aggregate.AggregateStore.AggregateConfig;
import com.infenia.yukta.message.aggregate.AggregateStore.AggregateResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryAggregateStoreTest {

  private InMemoryAggregateStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryAggregateStore();
    store.init();
  }

  @AfterEach
  void tearDown() {
    store.shutdown();
  }

  @Test
  void testSumWithCount() {
    final String key = "sum-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 2, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    StepVerifier.create(store.addValue(key, 10.0, msg, config))
        .expectNextMatches(res -> res.status() == AggregateResult.Status.WAITING)
        .verifyComplete();

    StepVerifier.create(store.addValue(key, 20.0, msg, config))
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED && (Double) res.result() == 30.0)
        .verifyComplete();
  }

  @Test
  void testAverageWithCount() {
    final String key = "avg-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 3, 0, "AVERAGE", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    store.addValue(key, 10.0, msg, config).block();
    store.addValue(key, 20.0, msg, config).block();

    StepVerifier.create(store.addValue(key, 30.0, msg, config))
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED && (Double) res.result() == 20.0)
        .verifyComplete();
  }

  @Test
  void testMinMaxWithCount() {
    final String key = "minmax-key";
    final AggregateConfig configMin =
        new AggregateConfig("COUNT", 2, 0, "MIN", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    store.addValue(key, 10.0, msg, configMin).block();
    StepVerifier.create(store.addValue(key, 5.0, msg, configMin))
        .expectNextMatches(
            res -> res.status() == AggregateResult.Status.COMPLETED && (Double) res.result() == 5.0)
        .verifyComplete();

    final AggregateConfig configMax =
        new AggregateConfig("COUNT", 2, 0, "MAX", 100, true, "IGNORE", null, null, null);
    store.addValue(key, 10.0, msg, configMax).block();
    StepVerifier.create(store.addValue(key, 15.0, msg, configMax))
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED && (Double) res.result() == 15.0)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCollectListWithCount() {
    final String key = "list-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 2, 0, "COLLECT_LIST", 100, true, "IGNORE", null, null, null);
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    store.addValue(key, "A", msg, config).block();
    StepVerifier.create(store.addValue(key, "B", msg, config))
        .expectNextMatches(
            res -> {
              final List<Object> list = (List<Object>) res.result();
              return res.status() == AggregateResult.Status.COMPLETED
                  && list.size() == 2
                  && list.contains("A")
                  && list.contains("B");
            })
        .verifyComplete();
  }

  @Test
  void testCustomAggregation() {
    final String key = "custom-key";
    final AggregateConfig config =
        new AggregateConfig(
            "COUNT", 2, 0, "CUSTOM", 100, true, "IGNORE", 0.0, "#acc + #val + 10", "#acc * 2");
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 1.0);

    store.addValue(key, 5.0, msg, config).block();
    // acc = 0.0 + 5.0 + 10 = 15.0

    StepVerifier.create(store.addValue(key, 5.0, msg, config))
        .expectNextMatches(
            res -> {
              // acc = 15.0 + 5.0 + 10 = 30.0
              // result = 30.0 * 2 = 60.0
              return res.status() == AggregateResult.Status.COMPLETED
                  && (Double) res.result() == 60.0;
            })
        .verifyComplete();
  }

  @Test
  void testTimeWindow() {
    final String key = "time-key";
    final AggregateConfig config =
        new AggregateConfig("TIME", 10, 50, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED
                    && res.key().equals(key)
                    && (Double) res.result() == 10.0)
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testSessionWindow() {
    final String key = "session-key";
    final AggregateConfig config =
        new AggregateConfig("SESSION", 10, 50, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    // Loop until COMPLETED
    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res -> res.status() == AggregateResult.Status.COMPLETED && res.key().equals(key))
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testLruEviction() {
    final String key1 = "key1";
    final String key2 = "key2";
    // Max pending windows = 1
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 1, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key1, 10.0, msg, config).block();

    // Adding key2 should evict key1
    StepVerifier.create(store.addValue(key2, 20.0, msg, config))
        .expectNextMatches(res -> res.status() == AggregateResult.Status.WAITING)
        .verifyComplete();

    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.EVICTED
                    && res.key().equals(key1)
                    && (Double) res.result() == 10.0)
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void testNullPolicy() {
    final String key = "null-key";
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    // IGNORE
    AggregateConfig configIgnore =
        new AggregateConfig("COUNT", 2, 0, "SUM", 100, true, "IGNORE", null, null, null);
    store.addValue(key, 10.0, msg, configIgnore).block();
    store.addValue(key, null, msg, configIgnore).block(); // ignored
    StepVerifier.create(store.addValue(key, 5.0, msg, configIgnore))
        .expectNextMatches(res -> (Double) res.result() == 15.0)
        .verifyComplete();

    // ZERO
    store.flush(key).block();
    AggregateConfig configZero =
        new AggregateConfig("COUNT", 2, 0, "SUM", 100, true, "ZERO", null, null, null);
    store.addValue(key, 10.0, msg, configZero).block();
    StepVerifier.create(store.addValue(key, null, msg, configZero))
        .expectNextMatches(res -> (Double) res.result() == 10.0) // 10 + 0
        .verifyComplete();

    // FAIL
    store.flush(key).block();
    AggregateConfig configFail =
        new AggregateConfig("COUNT", 2, 0, "SUM", 100, true, "FAIL", null, null, null);
    assertThrows(
        IllegalArgumentException.class, () -> store.addValue(key, null, msg, configFail).block());
  }

  @Test
  void testTimeout() {
    final String key = "timeout-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 100, "SUM", 100, false, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    // emitOnTimeout = false means it should NOT be in async results as COMPLETED
    StepVerifier.create(store.getAsyncResults()).expectTimeout(Duration.ofMillis(300)).verify();
  }

  @Test
  void testFlushAll() {
    final String key = "flush-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    StepVerifier.create(store.flushAll("flush-"))
        .expectNextMatches(res -> res.key().equals(key))
        .verifyComplete();
  }

  @Test
  void testFlushAllNoPrefix() {
    final String key = "any-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    StepVerifier.create(store.flushAll())
        .expectNextMatches(res -> res.key().equals(key))
        .verifyComplete();
  }

  @Test
  void testFlushNonExistent() {
    StepVerifier.create(store.flush("non-existent")).expectNextCount(0).verifyComplete();
  }

  @Test
  void testFirstLastAggregation() {
    final String keyFirst = "first-key";
    final AggregateConfig configFirst =
        new AggregateConfig("COUNT", 2, 0, "FIRST", 100, true, "IGNORE", null, null, null);
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    store.addValue(keyFirst, "A", msg, configFirst).block();
    StepVerifier.create(store.addValue(keyFirst, "B", msg, configFirst))
        .expectNextMatches(res -> res.result().equals("A"))
        .verifyComplete();

    final String keyLast = "last-key";
    final AggregateConfig configLast =
        new AggregateConfig("COUNT", 2, 0, "LAST", 100, true, "IGNORE", null, null, null);
    store.addValue(keyLast, "A", msg, configLast).block();
    StepVerifier.create(store.addValue(keyLast, "B", msg, configLast))
        .expectNextMatches(res -> res.result().equals("B"))
        .verifyComplete();
  }

  @Test
  void testNumericConversion() {
    final String key = "num-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 2, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    store.addValue(key, "10.5", msg, config).block();
    StepVerifier.create(store.addValue(key, "invalid", msg, config))
        .expectNextMatches(res -> (Double) res.result() == 10.5) // "invalid" converts to 0.0
        .verifyComplete();
  }

  @Test
  void testAverageZeroCount() throws Exception {
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "AVERAGE", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    // Using reflection to create state and get result without updates
    java.lang.reflect.Constructor<?> constructor =
        Class.forName("com.infenia.yukta.message.aggregate.InMemoryAggregateStore$AggregateState")
            .getDeclaredConstructor(Message.class, AggregateConfig.class);
    constructor.setAccessible(true);
    Object state = constructor.newInstance(msg, config);

    java.lang.reflect.Method getFinalResult = state.getClass().getDeclaredMethod("getFinalResult");
    getFinalResult.setAccessible(true);
    assertEquals(0.0, getFinalResult.invoke(state));
  }

  @Test
  void testCustomInit() {
    final String key = "custom-init";
    final AggregateConfig config =
        new AggregateConfig(
            "COUNT", 1, 0, "CUSTOM", 100, true, "IGNORE", 100.0, "#acc + #val", null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    StepVerifier.create(store.addValue(key, 5.0, msg, config))
        .expectNextMatches(res -> (Double) res.result() == 105.0)
        .verifyComplete();
  }

  @Test
  void testExpiredDiscard() throws Exception {
    final String key = "expire-discard";
    // emitOnTimeout = false
    final AggregateConfig config =
        new AggregateConfig("TIME", 10, 1, "SUM", 100, false, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    Thread.sleep(10); // Ensure expiration

    // Manually trigger cleanup via reflection
    java.lang.reflect.Method cleanup = InMemoryAggregateStore.class.getDeclaredMethod("cleanup");
    cleanup.setAccessible(true);
    cleanup.invoke(store);

    // Should be gone
    StepVerifier.create(store.flush(key)).expectNextCount(0).verifyComplete();
  }

  @Test
  void testShutdownBranches() throws Exception {
    InMemoryAggregateStore testStore = new InMemoryAggregateStore();
    testStore.init();

    java.lang.reflect.Field schedulerField =
        InMemoryAggregateStore.class.getDeclaredField("scheduler");
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

    // Test InterruptedException
    InMemoryAggregateStore interruptStore = new InMemoryAggregateStore();
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

  @Test
  void testDefaultAggType() throws Exception {
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "UNKNOWN", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    java.lang.reflect.Constructor<?> constructor =
        Class.forName("com.infenia.yukta.message.aggregate.InMemoryAggregateStore$AggregateState")
            .getDeclaredConstructor(Message.class, AggregateConfig.class);
    constructor.setAccessible(true);
    Object state = constructor.newInstance(msg, config);

    java.lang.reflect.Method update =
        state.getClass().getDeclaredMethod("update", Object.class, Message.class);
    update.setAccessible(true);
    update.invoke(state, 10.0, msg);

    java.lang.reflect.Method getFinalResult = state.getClass().getDeclaredMethod("getFinalResult");
    getFinalResult.setAccessible(true);
    assertNull(getFinalResult.invoke(state));
  }

  @Test
  void testConvertToNumberSpecialAggs() throws Exception {
    // Branch: !AGG_LIST.equals(aggType) && !AGG_CUSTOM.equals(aggType) is false
    final AggregateConfig configList =
        new AggregateConfig("COUNT", 10, 0, "COLLECT_LIST", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);

    java.lang.reflect.Constructor<?> constructor =
        Class.forName("com.infenia.yukta.message.aggregate.InMemoryAggregateStore$AggregateState")
            .getDeclaredConstructor(Message.class, AggregateConfig.class);
    constructor.setAccessible(true);
    Object stateList = constructor.newInstance(msg, configList);

    java.lang.reflect.Method convertToNumber =
        stateList.getClass().getDeclaredMethod("convertToNumber", Object.class);
    convertToNumber.setAccessible(true);
    assertEquals(0.0, convertToNumber.invoke(stateList, "not-a-number"));

    final AggregateConfig configCustom =
        new AggregateConfig("COUNT", 10, 0, "CUSTOM", 100, true, "IGNORE", 0.0, "0", "0");
    Object stateCustom = constructor.newInstance(msg, configCustom);
    assertEquals(0.0, convertToNumber.invoke(stateCustom, "not-a-number"));
  }

  @Test
  void testIsExpiredBranches() throws Exception {
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 0.0);
    java.lang.reflect.Constructor<?> constructor =
        Class.forName("com.infenia.yukta.message.aggregate.InMemoryAggregateStore$AggregateState")
            .getDeclaredConstructor(Message.class, AggregateConfig.class);
    constructor.setAccessible(true);

    // Session window
    final AggregateConfig configSession =
        new AggregateConfig("SESSION", 0, 1000, "SUM", 100, true, "IGNORE", null, null, null);
    Object stateSession = constructor.newInstance(msg, configSession);
    java.lang.reflect.Method isExpired =
        stateSession.getClass().getDeclaredMethod("isExpired", long.class);
    isExpired.setAccessible(true);

    long startTime = System.currentTimeMillis();
    org.junit.jupiter.api.Assertions.assertFalse(
        (boolean) isExpired.invoke(stateSession, startTime + 500));
    org.junit.jupiter.api.Assertions.assertTrue(
        (boolean) isExpired.invoke(stateSession, startTime + 1500));

    // Time window
    final AggregateConfig configTime =
        new AggregateConfig("TIME", 0, 1000, "SUM", 100, true, "IGNORE", null, null, null);
    Object stateTime = constructor.newInstance(msg, configTime);
    org.junit.jupiter.api.Assertions.assertFalse(
        (boolean) isExpired.invoke(stateTime, startTime + 500));
    org.junit.jupiter.api.Assertions.assertTrue(
        (boolean) isExpired.invoke(stateTime, startTime + 1500));
  }

  @Test
  void testEmitAndRemoveNull() throws Exception {
    // Call emitAndRemove with a key that doesn't exist
    java.lang.reflect.Method emitAndRemove =
        InMemoryAggregateStore.class.getDeclaredMethod("emitAndRemove", String.class);
    emitAndRemove.setAccessible(true);
    emitAndRemove.invoke(store, "non-existent-key");
  }

  @Test
  void testFlushAllPrefixMismatch() {
    final String key = "prefix-key";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    // Mismatched prefix should return nothing
    StepVerifier.create(store.flushAll("other-")).expectNextCount(0).verifyComplete();
  }

  @Test
  void testFlushExisting() {
    final String key = "flush-existing";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    StepVerifier.create(store.flush(key))
        .expectNextMatches(
            res -> res.status() == AggregateResult.Status.COMPLETED && res.key().equals(key))
        .verifyComplete();
  }

  @Test
  void testAddExistingKeyWhenFull() {
    final String key1 = "key1";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 1, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    // Fill the store
    store.addValue(key1, 10.0, msg, config).block();
    assertEquals(1, ((java.util.Map<?, ?>) getStoreField()).size());

    // Add to existing key - should NOT trigger eviction even if size >= maxPendingWindows
    StepVerifier.create(store.addValue(key1, 20.0, msg, config))
        .expectNextMatches(res -> res.status() == AggregateResult.Status.WAITING)
        .verifyComplete();

    assertEquals(1, ((java.util.Map<?, ?>) getStoreField()).size());
  }

  @Test
  void testMaxPendingWindowsZero() {
    final String key = "zero-max";
    final AggregateConfig config =
        new AggregateConfig("COUNT", 10, 0, "SUM", 0, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    // size=0, max=0. size >= max is true. But iterator.hasNext() will be false.
    StepVerifier.create(store.addValue(key, 10.0, msg, config))
        .expectNextMatches(res -> res.status() == AggregateResult.Status.WAITING)
        .verifyComplete();
  }

  private Object getStoreField() {
    try {
      java.lang.reflect.Field field = InMemoryAggregateStore.class.getDeclaredField("store");
      field.setAccessible(true);
      return field.get(store);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void assertEquals(Object expected, Object actual) {

    if (expected == null && actual == null) return;
    if (expected != null && expected.equals(actual)) return;
    throw new RuntimeException("Expected " + expected + " but got " + actual);
  }

  private void assertNull(Object actual) {
    if (actual != null) throw new RuntimeException("Expected null but got " + actual);
  }

  private void assertThrows(Class<? extends Throwable> clazz, Runnable runnable) {
    try {
      runnable.run();
      throw new RuntimeException("Expected " + clazz.getName());
    } catch (Throwable t) {
      if (!clazz.isInstance(t)) {
        if (t.getCause() != null && clazz.isInstance(t.getCause())) {
          return;
        }
        throw t;
      }
    }
  }
}
