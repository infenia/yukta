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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.MessageStore;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamTopologyDecoratorTest {

  @Mock private MessageStore messageStore;

  @Mock private DefaultTaskTrackerService tracker;

  private NodeCheckpointStore checkpointStore;
  private StreamTopologyDecorator decorator;

  @BeforeEach
  void setUp() {
    checkpointStore = new InMemoryNodeCheckpointStore();
    decorator = new StreamTopologyDecorator(messageStore, tracker, checkpointStore);
    when(messageStore.store(any())).thenReturn(Mono.empty());
  }

  @Test
  void testMergeParentStreamsEmpty() {
    final Flux<Message<?>>[] streams = new Flux[0];
    final ParentEdgeInfo[] parentEdges = new ParentEdgeInfo[0];

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges)).verifyComplete();
  }

  @Test
  void testMergeParentStreamsSingle() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg)};
    final ParentEdgeInfo[] parentEdges =
        new ParentEdgeInfo[] {new ParentEdgeInfo(0, "parent-1", null)};

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges))
        .assertNext(m -> assertThat(m.getSourceNodeId()).isEqualTo("parent-1"))
        .verifyComplete();
  }

  @Test
  void testMergeParentStreamsMultiple() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "data1");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "data2");
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1), Flux.just(msg2)};
    final ParentEdgeInfo[] parentEdges =
        new ParentEdgeInfo[] {
          new ParentEdgeInfo(0, "parent-1", null), new ParentEdgeInfo(1, "parent-2", null)
        };

    StepVerifier.create(decorator.mergeParentStreams(streams, parentEdges))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  void testApplyEdgeRoutingWithPort() {
    final Message<?> msg1 =
        DefaultMessage.create(UUID.randomUUID(), "data").withSourcePort("port-a");
    final Message<?> msg2 =
        DefaultMessage.create(UUID.randomUUID(), "data").withSourcePort("port-b");
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1, msg2)};
    final ParentEdgeInfo edge = new ParentEdgeInfo(0, "parent-1", "port-a");

    StepVerifier.create(decorator.applyEdgeRouting(streams, edge))
        .assertNext(m -> assertThat(m.getSourceNodeId()).isEqualTo("parent-1"))
        .verifyComplete();
  }

  @Test
  void testApplyEdgeRoutingNoPort() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "data");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1, msg2)};
    final ParentEdgeInfo edge = new ParentEdgeInfo(0, "parent-1", null);

    StepVerifier.create(decorator.applyEdgeRouting(streams, edge))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  void testApplyLoggingAndBroadcasting() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            "exec-1", "node-1", input, 1024, disposables, connectors);

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
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "data1");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "data2");
    final Flux<Message<?>> input = Flux.just(msg1, msg2);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            "exec-1", "node-1", input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectNextCount(2).verifyComplete();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingWithoutMessageStore() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final NodeCheckpointStore checkpointStore = new InMemoryNodeCheckpointStore();
    final StreamTopologyDecorator decoratorWithoutStore =
        new StreamTopologyDecorator(null, tracker, checkpointStore);

    final Flux<Message<?>> output =
        decoratorWithoutStore.applyLoggingAndBroadcasting(
            "exec-1", "node-1", input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectNextCount(1).verifyComplete();

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
        decorator.applyLoggingAndBroadcasting(
            "exec-1", "node-1", input, 1024, disposables, connectors);

    // Execute deferred connector subscriptions
    for (final Runnable connector : connectors) {
      connector.run();
    }

    StepVerifier.create(output).expectError(RuntimeException.class).verify();

    // Cleanup
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testMergeParentStreamsUsingVarargs() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "data1");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "data2");
    final Message<?> msg3 = DefaultMessage.create(UUID.randomUUID(), "data3");
    final Flux<Message<?>>[] streams =
        new Flux[] {Flux.just(msg1), Flux.just(msg2), Flux.just(msg3)};

    StepVerifier.create(
            decorator.mergeParentStreams(
                streams,
                new ParentEdgeInfo(0, "parent-1", null),
                new ParentEdgeInfo(1, "parent-2", null),
                new ParentEdgeInfo(2, "parent-3", null)))
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
}
