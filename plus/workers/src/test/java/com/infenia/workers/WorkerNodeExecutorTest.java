// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.exception.RemoteFailureException;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.WorkerRegistry;
import com.infenia.yukta.service.worker.WorkerRegistryFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.SynchronousSink;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.GuardLogStatement", "PMD.AvoidDuplicateLiterals", "unchecked"})
class WorkerNodeExecutorTest {

  private final WorkerNodeExecutor executor =
      new WorkerNodeExecutor(
          WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry()),
          new WorkerConnections(),
          new ObjectMapper(),
          (executionId, nodeId) -> null,
          ObservationRegistry.NOOP,
          new SimpleMeterRegistry());

  @Test
  void unexpectedFrameOnTheWayBack_failsTheAttempt() {
    final SynchronousSink<com.infenia.yukta.message.Message<?>> sink = mock(SynchronousSink.class);

    executor.answer(
        "w1",
        NodeFrame.start("e", "n", "T", Map.of(), Map.of()),
        () -> {},
        PluginLogSink.NOOP,
        sink);

    final ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(sink).error(error.capture());
    assertThat(error.getValue())
        .isInstanceOf(WorkflowExecutionException.class)
        .hasMessageContaining("Unexpected frame from w1");
  }

  @Test
  void logFrame_goesToTheNodesLogSink_withItsLevelWhenKnown() {
    final SynchronousSink<com.infenia.yukta.message.Message<?>> sink = mock(SynchronousSink.class);
    final PluginLogSink logSink = mock(PluginLogSink.class);

    executor.answer("w1", NodeFrame.log("stderr", "boom", LogLevel.ERROR), () -> {}, logSink, sink);
    executor.answer("w1", NodeFrame.log("stdout", "plain", null), () -> {}, logSink, sink);
    executor.answer(
        "w1",
        new NodeFrame(
            NodeFrame.Kind.LOG,
            null,
            null,
            null,
            null,
            Map.of("stream", "stdout", "line", "newer", "level", "TRACE_PLUS"),
            null,
            null,
            null),
        () -> {},
        logSink,
        sink);
    executor.answer(
        "w1",
        new NodeFrame(NodeFrame.Kind.LOG, null, null, null, null, null, null, null, null),
        () -> {},
        logSink,
        sink);

    verify(logSink).line("stderr", "boom", LogLevel.ERROR);
    verify(logSink).line("stdout", "plain", null);
    verify(logSink).line("stdout", "newer", null);
    verify(logSink).line("stdout", "", null);
    org.mockito.Mockito.verifyNoInteractions(sink);
  }

  @Test
  void errorFrame_keepsTheClassChainSoRetryListsMatch() {
    final SynchronousSink<com.infenia.yukta.message.Message<?>> sink = mock(SynchronousSink.class);
    final NodeFrame frame =
        NodeFrame.error(
            "java.net.SocketTimeoutException",
            "slow",
            List.of(
                "java.net.SocketTimeoutException",
                "java.io.InterruptedIOException",
                "java.io.IOException"));

    executor.answer("w1", frame, () -> {}, PluginLogSink.NOOP, sink);

    final ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(sink).error(error.capture());
    assertThat(error.getValue()).isInstanceOf(RemoteFailureException.class);
    assertThat(
            com.infenia.yukta.model.workflow.NodePolicy.from(
                    Map.of("engine", Map.of("retry", Map.of("noRetryOn", List.of("IOException")))))
                .retry()
                .shouldRetry(error.getValue()))
        .isFalse();
  }

  @Test
  void errorFrameWithoutClassChain_stillNamesItsClass() {
    final SynchronousSink<com.infenia.yukta.message.Message<?>> sink = mock(SynchronousSink.class);
    final NodeFrame frame =
        new NodeFrame(NodeFrame.Kind.ERROR, null, null, null, null, null, "a.Old", "x", null);

    executor.answer("w1", frame, () -> {}, PluginLogSink.NOOP, sink);

    final ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(sink).error(error.capture());
    final Throwable thrown = error.getValue();
    assertThat(thrown)
        .isInstanceOfSatisfying(
            RemoteFailureException.class,
            remote -> assertThat(remote.remoteTypes()).containsExactly("a.Old"));
  }

  @Test
  void pick_prefersFreeSlots_thenLowestId_skipsUnconnected() {
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    final WorkerNodeExecutor picking =
        new WorkerNodeExecutor(
            registry,
            connections,
            new ObjectMapper(),
            (executionId, nodeId) -> null,
            ObservationRegistry.NOOP,
            new SimpleMeterRegistry());
    final org.springframework.messaging.rsocket.RSocketRequester requester =
        mock(org.springframework.messaging.rsocket.RSocketRequester.class);
    register(registry, "w2", 3);
    register(registry, "w1", 3);
    register(registry, "w3", 3);
    register(registry, "w4", 9);
    connections.put("w1", requester);
    connections.put("w2", requester);
    connections.put("w4", requester);
    registry.heartbeat("w4", 9);

    assertThat(picking.pick("default", "T"))
        .hasValueSatisfying(chosen -> assertThat(chosen.info().workerId()).isEqualTo("w1"));
    assertThat(picking.pick("default", "OTHER")).isEmpty();
  }

  @Test
  void run_sendsTheTraceHeadersInStart_asChildOfTheNode_andEndsTheSpanWithTheAttempt() {
    final RecordingHandler traced = new RecordingHandler();
    final ObservationRegistry observations = traced.registry();
    final Observation node = Observation.start("yukta.node", observations);
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    register(registry, "w1", 2);
    final org.springframework.messaging.rsocket.RSocketRequester requester =
        mock(org.springframework.messaging.rsocket.RSocketRequester.class);
    final org.springframework.messaging.rsocket.RSocketRequester.RequestSpec spec =
        mock(org.springframework.messaging.rsocket.RSocketRequester.RequestSpec.class);
    final org.springframework.messaging.rsocket.RSocketRequester.RetrieveSpec retrieve =
        mock(org.springframework.messaging.rsocket.RSocketRequester.RetrieveSpec.class);
    org.mockito.Mockito.when(requester.route("node.run")).thenReturn(spec);
    org.mockito.Mockito.when(spec.data(org.mockito.ArgumentMatchers.any())).thenReturn(retrieve);
    org.mockito.Mockito.when(retrieve.retrieveFlux(NodeFrame.class))
        .thenReturn(reactor.core.publisher.Flux.just(NodeFrame.complete()))
        .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("gone")));
    connections.put("w1", requester);
    final WorkerNodeExecutor tracing =
        new WorkerNodeExecutor(
            registry,
            connections,
            new ObjectMapper(),
            (executionId, nodeId) -> "e1".equals(executionId) ? node : null,
            observations,
            new SimpleMeterRegistry());
    final reactor.util.context.Context context =
        reactor.util.context.Context.of("executionId", "e1", "nodeId", "n1");

    reactor.test.StepVerifier.create(
            tracing
                .run("T", "default", reactor.core.publisher.Flux.empty(), Map.of())
                .contextWrite(context))
        .verifyComplete();

    final ArgumentCaptor<org.reactivestreams.Publisher<NodeFrame>> sent =
        ArgumentCaptor.forClass(org.reactivestreams.Publisher.class);
    verify(spec).data(sent.capture());
    final NodeFrame start = reactor.core.publisher.Flux.from(sent.getValue()).blockFirst();
    assertThat(start.kind()).isEqualTo(NodeFrame.Kind.START);
    assertThat(start.message()).containsEntry("traceparent", RecordingHandler.TRACEPARENT);
    final Observation.Context call = traced.started.get(1);
    assertThat(call.getName()).isEqualTo("yukta.worker.call");
    assertThat(call.getParentObservation()).isSameAs(node);
    assertThat(traced.stopped).containsExactly(call);

    reactor.test.StepVerifier.create(
            tracing
                .run("T", "default", reactor.core.publisher.Flux.empty(), Map.of())
                .contextWrite(context))
        .expectError(WorkflowExecutionException.class)
        .verify(Duration.ofSeconds(5));
    assertThat(traced.failed).hasSize(1);
  }

  @Test
  void pick_skipsQuarantinedWorker() {
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    final WorkerHealth health = new WorkerHealth(System::nanoTime);
    final WorkerNodeExecutor picking =
        new WorkerNodeExecutor(
            registry,
            connections,
            new ObjectMapper(),
            (executionId, nodeId) -> null,
            ObservationRegistry.NOOP,
            new SimpleMeterRegistry(),
            health);
    register(registry, "w1", 5);
    register(registry, "w2", 1);
    connections.put("w1", mock(org.springframework.messaging.rsocket.RSocketRequester.class));
    connections.put("w2", mock(org.springframework.messaging.rsocket.RSocketRequester.class));
    health.failed("w1", "T");
    health.succeeded("w2", "T");
    for (int failure = 1; failure < WorkerHealth.FAILURES; failure++) {
      health.failed("w1", "T");
    }

    assertThat(picking.pick("default", "T"))
        .hasValueSatisfying(chosen -> assertThat(chosen.info().workerId()).isEqualTo("w2"));
  }

  @Test
  void failuresOfTheWorker_quarantineIt_butBadInputDoesNotCount() {
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    final WorkerHealth health = new WorkerHealth(System::nanoTime);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final WorkerNodeExecutor failing =
        new WorkerNodeExecutor(
            registry,
            connections,
            new ObjectMapper(),
            (executionId, nodeId) -> null,
            ObservationRegistry.NOOP,
            meters,
            health);
    register(registry, "w1", 5);
    final org.springframework.messaging.rsocket.RSocketRequester requester =
        mock(org.springframework.messaging.rsocket.RSocketRequester.class);
    final org.springframework.messaging.rsocket.RSocketRequester.RequestSpec spec =
        mock(org.springframework.messaging.rsocket.RSocketRequester.RequestSpec.class);
    final org.springframework.messaging.rsocket.RSocketRequester.RetrieveSpec retrieve =
        mock(org.springframework.messaging.rsocket.RSocketRequester.RetrieveSpec.class);
    org.mockito.Mockito.when(requester.route("node.run")).thenReturn(spec);
    org.mockito.Mockito.when(spec.data(org.mockito.ArgumentMatchers.any())).thenReturn(retrieve);
    final NodeFrame broken = NodeFrame.error("a.Broken", "no disk", List.of("a.Broken"));
    final NodeFrame badInput =
        NodeFrame.error(IllegalArgumentException.class.getName(), "bad", List.of());
    org.mockito.Mockito.when(retrieve.retrieveFlux(NodeFrame.class))
        .thenReturn(reactor.core.publisher.Flux.just(badInput))
        .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("gone")))
        .thenReturn(reactor.core.publisher.Flux.just(broken));
    connections.put("w1", requester);
    health.failed("w1", "T");
    health.succeeded("w2", "T");

    for (int attempt = 0; attempt < WorkerHealth.FAILURES; attempt++) {
      reactor.test.StepVerifier.create(
              failing.run("T", "default", reactor.core.publisher.Flux.empty(), Map.of()))
          .expectError()
          .verify(Duration.ofSeconds(5));
    }

    // the first failure was counted before the loop; bad input did not count, the rest did
    assertThat(health.quarantined("w1")).isTrue();
    assertThat(meters.counter("yukta.worker.quarantined", "module", "T").count()).isOne();
  }

  @Test
  void pick_takesTheSlot_soPicksAtTheSameMomentNeverOverbookTheWorker() {
    final WorkerRegistry registry =
        WorkerRegistryFixture.create(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    final WorkerNodeExecutor picking =
        new WorkerNodeExecutor(
            registry,
            connections,
            new ObjectMapper(),
            (executionId, nodeId) -> null,
            ObservationRegistry.NOOP,
            new SimpleMeterRegistry());
    register(registry, "w1", 5);
    connections.put("w1", mock(org.springframework.messaging.rsocket.RSocketRequester.class));

    final long taken =
        java.util.stream.IntStream.range(0, 200)
            .parallel()
            .filter(_ -> picking.pick("default", "T").isPresent())
            .count();

    assertThat(taken).isEqualTo(5);
  }

  private static void register(
      final WorkerRegistry registry, final String name, final int capacity) {
    registry.register(
        new com.infenia.yukta.service.worker.WorkerRegistration(
            name, java.util.Set.of("default"), java.util.Set.of("T"), capacity));
  }
}
