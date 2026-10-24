// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.SynchronousSink;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.GuardLogStatement", "unchecked"})
class WorkerNodeExecutorTest {

  private final WorkerNodeExecutor executor =
      new WorkerNodeExecutor(
          new WorkerRegistry(Duration.ofSeconds(15), new SimpleMeterRegistry()),
          new WorkerConnections(),
          new ObjectMapper());

  @Test
  void unexpectedFrameOnTheWayBack_failsTheAttempt() {
    final SynchronousSink<com.infenia.yukta.message.Message<?>> sink = mock(SynchronousSink.class);

    executor.answer("w1", NodeFrame.start("e", "n", "T", Map.of()), () -> {}, sink);

    final ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(sink).error(error.capture());
    assertThat(error.getValue())
        .isInstanceOf(WorkflowExecutionException.class)
        .hasMessageContaining("Unexpected frame from w1");
  }

  @Test
  void pick_prefersFreeSlots_thenLowestId_skipsUnconnected() {
    final WorkerRegistry registry =
        new WorkerRegistry(Duration.ofSeconds(15), new SimpleMeterRegistry());
    final WorkerConnections connections = new WorkerConnections();
    final WorkerNodeExecutor picking =
        new WorkerNodeExecutor(registry, connections, new ObjectMapper());
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

  private static void register(
      final WorkerRegistry registry, final String name, final int capacity) {
    registry.register(
        new com.infenia.yukta.service.worker.WorkerRegistration(
            name, java.util.Set.of("default"), java.util.Set.of("T"), capacity));
  }
}
