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
package com.infenia.yukta.service.resequence;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceConfig;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
