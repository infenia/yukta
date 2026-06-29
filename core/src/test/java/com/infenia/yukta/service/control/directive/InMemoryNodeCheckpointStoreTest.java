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
package com.infenia.yukta.service.control.directive;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CommentRequired"})
@NoArgsConstructor
class InMemoryNodeCheckpointStoreTest {

  private InMemoryNodeCheckpointStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryNodeCheckpointStore();
  }

  @Test
  void testSaveAndGet() {
    final String executionId = "exec-1";
    final String nodeId = "node-1";
    final Message<String> message = DefaultMessage.create(UUID.randomUUID(), "test-data");

    StepVerifier.create(store.save(executionId, nodeId, message)).verifyComplete();

    StepVerifier.create(store.get(executionId, nodeId))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("test-data"))
        .verifyComplete();
  }

  @Test
  void testGetNonExistentExecution() {
    StepVerifier.create(store.get("non-existent", "node-1")).verifyComplete();
  }

  @Test
  void testGetNonExistentNode() {
    final String executionId = "exec-1";
    final String nodeId = "node-1";
    final Message<String> message = DefaultMessage.create(UUID.randomUUID(), "test-data");

    StepVerifier.create(store.save(executionId, nodeId, message)).verifyComplete();

    StepVerifier.create(store.get(executionId, "non-existent-node")).verifyComplete();
  }

  @Test
  void testClear() {
    final String executionId = "exec-1";
    final String nodeId = "node-1";
    final Message<String> message = DefaultMessage.create(UUID.randomUUID(), "test-data");

    StepVerifier.create(store.save(executionId, nodeId, message)).verifyComplete();

    store.clear(executionId);

    StepVerifier.create(store.get(executionId, nodeId)).verifyComplete();
  }

  @Test
  void testMultipleNodesPerExecution() {
    final String executionId = "exec-1";
    final Message<String> msg1 = DefaultMessage.create(UUID.randomUUID(), "data-1");
    final Message<String> msg2 = DefaultMessage.create(UUID.randomUUID(), "data-2");

    StepVerifier.create(store.save(executionId, "node-1", msg1)).verifyComplete();
    StepVerifier.create(store.save(executionId, "node-2", msg2)).verifyComplete();

    StepVerifier.create(store.get(executionId, "node-1"))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("data-1"))
        .verifyComplete();

    StepVerifier.create(store.get(executionId, "node-2"))
        .assertNext(msg -> assertThat(msg.getPayload()).isEqualTo("data-2"))
        .verifyComplete();
  }

  @Test
  void testMultipleExecutions() {
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(store.save("exec-1", "node-1", msg)).verifyComplete();
    StepVerifier.create(store.save("exec-2", "node-1", msg)).verifyComplete();

    StepVerifier.create(store.get("exec-1", "node-1")).expectNextCount(1).verifyComplete();

    StepVerifier.create(store.get("exec-2", "node-1")).expectNextCount(1).verifyComplete();
  }
}
