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
package com.infenia.yukta.service.aggregate;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateConfig;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateResult;
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
        new AggregateConfig("TIME", 100, 200, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED
                    && res.key().equals(key)
                    && (Double) res.result() == 10.0)
        .thenCancel()
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void testSessionWindow() {
    final String key = "session-key";
    final AggregateConfig config =
        new AggregateConfig("SESSION", 100, 300, "SUM", 100, true, "IGNORE", null, null, null);
    final Message<Double> msg = DefaultMessage.create(UUID.randomUUID(), 10.0);

    store.addValue(key, 10.0, msg, config).block();

    // Reset session
    try {
      Thread.sleep(150);
    } catch (InterruptedException e) {
    }
    store.addValue(key, 20.0, msg, config).block();

    StepVerifier.create(store.getAsyncResults())
        .expectNextMatches(
            res ->
                res.status() == AggregateResult.Status.COMPLETED
                    && res.key().equals(key)
                    && (Double) res.result() == 30.0)
        .thenCancel()
        .verify(Duration.ofSeconds(2));
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
