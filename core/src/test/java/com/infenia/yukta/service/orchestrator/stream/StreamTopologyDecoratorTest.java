// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for StreamTopologyDecorator with message stream orchestration. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseShortArrayInitializer"})
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - ")
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

  /** Routes a node output through one outgoing edge, as the compiler does. */
  @SuppressWarnings(UNCHECKED)
  private Flux<Message<?>> edge(final Flux<Message<?>> output) {
    return output;
  }

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
        .assertNext(m -> assertThat(m.sourceNodeId()).isEqualTo(PARENT_1))
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
  void testApplyLoggingAndBroadcasting() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    StepVerifier.create(edge(output)).expectNextCount(1).verifyComplete();

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

    StepVerifier.create(edge(output)).expectNextCount(2).verifyComplete();

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

    StepVerifier.create(edge(output)).expectError(RuntimeException.class).verify();

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
  void testApplyLoggingAndBroadcastingMessageHistory() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>> input = Flux.just(msg);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(EXEC_1, NODE_1, input, 1024, disposables, connectors);

    StepVerifier.create(edge(output))
        .assertNext(m -> assertThat(m.messageHistory()).contains("node-1"))
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
  void testApplyLoggingAndBroadcastingBlankPayloadIsPassedOnWithoutLogLine() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "");
    final List<Disposable> disposables = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            EXEC_1, NODE_1, Flux.just(msg), 1024, disposables, new ArrayList<>());
    StepVerifier.create(edge(output))
        .assertNext(out -> assertThat(out.payload()).isEqualTo(""))
        .verifyComplete();

    Mockito.verify(tracker, Mockito.never())
        .emitLogEvent(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingSkipsEchoForNodeThatLoggedLive() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x".repeat(5_000));
    final List<Disposable> disposables = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            EXEC_1, NODE_1, Flux.just(msg), 1024, disposables, new ArrayList<>(), () -> true);
    StepVerifier.create(edge(output)).expectNextCount(1).verifyComplete();

    Mockito.verify(tracker, Mockito.never())
        .emitLogEvent(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingSkipsEchoForLiveLoggedNodeEvenWhenTracing() {
    final Logger logger = (Logger) LoggerFactory.getLogger(StreamTopologyDecorator.class);
    final Level previous = logger.getLevel();
    logger.setLevel(Level.TRACE);
    try {
      final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "already on screen");
      final List<Disposable> disposables = new ArrayList<>();

      final Flux<Message<?>> output =
          decorator.applyLoggingAndBroadcasting(
              EXEC_1, NODE_1, Flux.just(msg), 1024, disposables, new ArrayList<>(), () -> true);
      StepVerifier.create(edge(output))
          .assertNext(out -> assertThat(out.payload()).isEqualTo("already on screen"))
          .verifyComplete();

      Mockito.verify(tracker, Mockito.never())
          .emitLogEvent(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

      disposables.forEach(Disposable::dispose);
    } finally {
      logger.setLevel(previous);
    }
  }

  @Test
  void testApplyLoggingAndBroadcastingLargePayloadIsTruncatedInLog() {
    final String largePayload = "0123456789".repeat(2000);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), largePayload);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();

    final Flux<Message<?>> output =
        decorator.applyLoggingAndBroadcasting(
            EXEC_1, NODE_1, Flux.just(msg), 1024, disposables, connectors);
    StepVerifier.create(edge(output)).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
    Mockito.verify(tracker, Mockito.times(1))
        .emitLogEvent(ArgumentMatchers.eq(EXEC_1), ArgumentMatchers.eq(NODE_1), line.capture());
    assertThat(line.getValue()).startsWith("0123456789").contains("[truncated 17952 chars]");
    assertThat(line.getValue().length()).isLessThan(2_100);
    // the message itself is not altered
    assertThat(msg.payload()).isEqualTo(largePayload);

    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testApplyLoggingAndBroadcastingDescribesBinaryAndHugeCollectionsInsteadOfPrintingThem() {
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = new ArrayList<>();
    final List<Object> payloads =
        List.of(
            new byte[5_000_000],
            java.util.stream.IntStream.range(0, 300).boxed().toList(),
            java.util.stream.IntStream.range(0, 300)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(i -> i, i -> i)));

    for (final Object payload : payloads) {
      final Flux<Message<?>> output =
          decorator.applyLoggingAndBroadcasting(
              EXEC_1,
              NODE_1,
              Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)),
              1024,
              disposables,
              connectors);
      StepVerifier.create(edge(output)).expectNextCount(1).verifyComplete();
    }

    final ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
    Mockito.verify(tracker, Mockito.times(3))
        .emitLogEvent(ArgumentMatchers.eq(EXEC_1), ArgumentMatchers.eq(NODE_1), lines.capture());
    assertThat(lines.getAllValues().get(0)).isEqualTo("<5000000 bytes>");
    assertThat(lines.getAllValues().get(1)).endsWith("of 300 elements");
    assertThat(lines.getAllValues().get(2)).endsWith("of 300 entries");
    disposables.forEach(Disposable::dispose);
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void mergeWithTolerance_keepsTheHealthyParentsMessages() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>>[] streams =
        new Flux[] {Flux.error(new IllegalStateException("down")), Flux.just(msg)};

    StepVerifier.create(
            decorator.mergeParentStreams(
                streams,
                new ParentEdgeInfo(0, PARENT_1, null, true),
                new ParentEdgeInfo(1, PARENT_2, null, true)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void oneSuccess_failsWhenEveryParentFailed() {
    final Flux<Message<?>>[] streams =
        new Flux[] {
          Flux.error(new IllegalStateException("down")), Flux.error(new IllegalStateException("x"))
        };

    StepVerifier.create(
            decorator.mergeParentStreams(
                NodePolicy.JoinRule.ONE_SUCCESS,
                streams,
                new ParentEdgeInfo(0, PARENT_1, null, true),
                new ParentEdgeInfo(1, PARENT_2, null, true)))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  @SuppressWarnings(UNCHECKED)
  void oneSuccess_continuesWhenOneParentSucceeded() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), DATA);
    final Flux<Message<?>>[] streams =
        new Flux[] {Flux.error(new IllegalStateException("down")), Flux.just(msg)};

    StepVerifier.create(
            decorator.mergeParentStreams(
                NodePolicy.JoinRule.ONE_SUCCESS,
                streams,
                new ParentEdgeInfo(0, PARENT_1, null, true),
                new ParentEdgeInfo(1, PARENT_2, null, true)))
        .expectNextCount(1)
        .verifyComplete();
  }
}
