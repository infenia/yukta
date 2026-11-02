// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@NoArgsConstructor
class DefaultInputGatewayTest {

  private final InputBroker broker =
      new InputBroker(
          new InMemoryInputRequestStore(10),
          new ExecutionEventFeed(),
          new InMemoryExecutionLogStore(),
          new SimpleMeterRegistry(),
          new InputRedactor(List.of()),
          limits(),
          Clock.systemUTC());
  private final DefaultInputGateway gateway = new DefaultInputGateway(broker);

  @Test
  void theRequestIsBoundToTheRunAndNodeOfTheContext() {
    gateway
        .ask(spec("k1"))
        .contextWrite(
            Context.of("sessionId", "s", "workflowId", "w", "executionId", "e", "nodeId", "n"))
        .subscribe();

    final InputRequest request = broker.listOpen(null, null).blockFirst();
    assertThat(request.sessionId()).isEqualTo("s");
    assertThat(request.workflowId()).isEqualTo("w");
    assertThat(request.executionId()).isEqualTo("e");
    assertThat(request.nodeId()).isEqualTo("n");
  }

  @Test
  void askingOutsideNodeFailsClearly() {
    StepVerifier.create(gateway.ask(spec("k1")))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Reactor context"))
        .verify();
  }
}
