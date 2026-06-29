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
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Tests for StreamTopologyDecorator with message stream orchestration. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseShortArrayInitializer"})
class StreamTopologyDecoratorTest {

  /** Test data payload. */
  private static final String DATA = "data";

  /** Test data payload 1. */
  private static final String DATA_1 = "data1";

  /** Test data payload 2. */
  private static final String DATA_2 = "data2";

  /** Test data payload 3. */
  private static final String DATA_3 = "data3";

  /** Parent node identifier 1. */
  private static final String PARENT_1 = "parent-1";

  /** Parent node identifier 2. */
  private static final String PARENT_2 = "parent-2";

  /** Parent node identifier 3. */
  private static final String PARENT_3 = "parent-3";

  /** Execution context identifier 1. */
  private static final String EXEC_1 = "exec-1";

  /** Node identifier 1. */
  private static final String NODE_1 = "node-1";

  /** Port identifier A. */
  private static final String PORT_A = "port-a";

  /** Port identifier B. */
  private static final String PORT_B = "port-b";

  /** Port identifier C. */
  private static final String PORT_C = "port-c";

  /** Suppress unchecked warnings for generic array creation. */
  private static final String UNCHECKED = "unchecked";

  /** Mock task tracker service for testing. */
  @Mock private DefaultTaskTrackerService tracker;

  /** Decorator instance under test. */
  private StreamTopologyDecorator decorator;

  @BeforeEach
  void setUp() {
    final NodeCheckpointStore checkpointStore = new InMemoryNodeCheckpointStore();
    decorator = new StreamTopologyDecorator(tracker, checkpointStore);
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testMergeParentStreamsEmpty() {
    final Flux<Message<?>>[] streams = new Flux[] {};
    final ParentEdgeInfo[] parentEdges = {};

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges)).verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testMergeParentStreamsSingle() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg)};
    final ParentEdgeInfo[] parentEdges = {new ParentEdgeInfo(0, PARENT_1, null)};

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges))
        .assertNext(m -> assertThat(m.getSourceNodeId()).isEqualTo(PARENT_1))
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testMergeParentStreamsMultiple() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA_1);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA_2);
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1), Flux.just(msg2)};
    final ParentEdgeInfo[] parentEdges = {
      new ParentEdgeInfo(0, PARENT_1, null), new ParentEdgeInfo(1, PARENT_2, null)
    };

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testApplyEdgeRoutingWithPort() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA).withSourcePort(PORT_A);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA).withSourcePort(PORT_B);
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1, msg2)};
    final ParentEdgeInfo edge = new ParentEdgeInfo(0, PARENT_1, PORT_A);

    StepVerifier.create(decorator.applyEdgeRouting(streams, edge))
        .assertNext(m -> assertThat(m.getSourceNodeId()).isEqualTo("parent-1"))
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testApplyEdgeRoutingNoPort() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1, msg2)};
    final ParentEdgeInfo edge = new ParentEdgeInfo(0, PARENT_1, null);

    StepVerifier.create(decorator.applyEdgeRouting(streams, edge))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  void testApplyLoggingAndBroadcasting() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectNextCount(1).verifyComplete();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingMulticast() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA_1);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA_2);
    final Flux<Message<?>> input = Flux.just(msg1, msg2);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectNextCount(2).verifyComplete();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingStreamError() {
    final RuntimeException testError = new RuntimeException("Test error");
    final Flux<Message<?>> input = Flux.error(testError);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectError(RuntimeException.class).verify();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testMergeParentStreamsUsingVarargs() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA_1);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA_2);
    final Message<?> msg3 = DefaultMessage.create(UUID.randomUUID(), DATA_3);
    final Flux<Message<?>>[] streams =
        new Flux[] {Flux.just(msg1), Flux.just(msg2), Flux.just(msg3)};

    StepVerifier.create(
            decorator.mergeParentStreams(
                streams,
                new ParentEdgeInfo(0, PARENT_1, null),
                new ParentEdgeInfo(1, PARENT_2, null),
                new ParentEdgeInfo(2, PARENT_3, null)))
        .expectNextCount(3)
        .verifyComplete();
  }

  @Test
  void testHandleEmitFailureWithNonSerializedResult() {
    assertThat(StreamTopologyDecorator.handleEmitFailure(Sinks.EmitResult.FAIL_NON_SERIALIZED))
        .isTrue();
  }

  @Test
  void testHandleEmitFailureWithOtherResult() {
    assertThat(StreamTopologyDecorator.handleEmitFailure(Sinks.EmitResult.FAIL_OVERFLOW)).isFalse();
    assertThat(StreamTopologyDecorator.handleEmitFailure(Sinks.EmitResult.OK)).isFalse();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void testApplyEdgeRoutingPortFilterNoMatch() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA).withSourcePort(PORT_A);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA).withSourcePort(PORT_B);
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1, msg2)};
    final ParentEdgeInfo edge = new ParentEdgeInfo(0, PARENT_1, PORT_C);

    StepVerifier.create(decorator.applyEdgeRouting(streams, edge)).verifyComplete();
  }

  @Test
  void testApplyLoggingAndBroadcastingMessageHistory() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output)
        .assertNext(m -> assertThat(m.getMessageHistory()).contains("node-1"))
        .verifyComplete();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingDisposablesPopulated() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), DATA_1);
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), DATA_2);
    final Flux<Message<?>> input = Flux.just(msg1, msg2);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Before running connectors, disposables should be empty
    assertThat(disposables).isEmpty();

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    // After running connectors, disposables should be populated
    assertThat(disposables).isNotEmpty();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingLargePayloadTruncation() {
    final StringBuilder largePayload = new StringBuilder();
    largePayload.repeat("0123456789", 2000);
    // largePayload is now 20,000 chars (exceeds MAX_LOG_LINE_SIZE of 16,384)
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), largePayload.toString());
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectNextCount(1).verifyComplete();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }
}
