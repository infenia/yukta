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
package com.infenia.yukta.service.join;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.service.join.JoinStore.JoinConfig;
import com.infenia.yukta.service.join.JoinStore.JoinResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemoryJoinStoreTest {

  private InMemoryJoinStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryJoinStore();
    store.init();
  }

  @AfterEach
  void tearDown() {
    store.shutdown();
  }

  @Test
  void testJoinAllCondition() {
    final String key = "test-key";
    final JoinConfig config = new JoinConfig("ALL", List.of("A", "B"), 1000, 0, 100);
    final Message msgA = Message.create(UUID.randomUUID(), "dataA");
    final Message msgB = Message.create(UUID.randomUUID(), "dataB");

    StepVerifier.create(store.addMessage(key, "A", msgA, config))
        .expectNextMatches(res -> res.status() == JoinResult.Status.WAITING)
        .verifyComplete();

    StepVerifier.create(store.addMessage(key, "B", msgB, config))
        .expectNextMatches(
            res ->
                res.status() == JoinResult.Status.COMPLETED && res.collectedMessages().size() == 2)
        .verifyComplete();
  }

  @Test
  void testJoinAnyCondition() {
    final String key = "test-key-any";
    final JoinConfig config = new JoinConfig("ANY", List.of("A", "B"), 1000, 0, 100);
    final Message msgA = Message.create(UUID.randomUUID(), "dataA");

    StepVerifier.create(store.addMessage(key, "A", msgA, config))
        .expectNextMatches(res -> res.status() == JoinResult.Status.COMPLETED)
        .verifyComplete();
  }

  @Test
  void testCustomCountCondition() {
    final String key = "test-key-count";
    final JoinConfig config = new JoinConfig("CUSTOM_COUNT", List.of(), 1000, 2, 100);
    final Message msgA = Message.create(UUID.randomUUID(), "dataA");
    final Message msgB = Message.create(UUID.randomUUID(), "dataB");

    StepVerifier.create(store.addMessage(key, "A", msgA, config))
        .expectNextMatches(res -> res.status() == JoinResult.Status.WAITING)
        .verifyComplete();

    StepVerifier.create(store.addMessage(key, "B", msgB, config))
        .expectNextMatches(res -> res.status() == JoinResult.Status.COMPLETED)
        .verifyComplete();
  }

  @Test
  void testMaxPendingJoins() {
    final JoinConfig config = new JoinConfig("ALL", List.of("A"), 1000, 0, 1);
    final Message msg = Message.create(UUID.randomUUID(), "data");

    store.addMessage("key1", "A", msg, config).block();

    StepVerifier.create(store.addMessage("key2", "A", msg, config))
        .expectNextMatches(res -> res.status() == JoinResult.Status.OVERFLOW)
        .verifyComplete();
  }
}
