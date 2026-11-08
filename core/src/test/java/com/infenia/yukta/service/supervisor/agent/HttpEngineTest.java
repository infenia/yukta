// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The {@code http} engine passes signals to the agent and the agent's directives back. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource"})
class HttpEngineTest {

  private final AgentHub hub = new AgentHub();
  private final HttpEngineFactory factory = new HttpEngineFactory(hub);

  private SupervisorEngine engine() {
    return factory.create(
        new EngineConfig(Map.of()),
        new EngineContext() {
          @Override
          public String self() {
            return "agent-1";
          }

          @Override
          public Flux<Instant> ticks(final Duration period) {
            return Flux.never();
          }

          @Override
          public Map<String, Object> state() {
            return Map.of();
          }

          @Override
          public EngineReads reads() {
            return EngineReads.NONE;
          }
        });
  }

  @Test
  void theFactoryDescribesAnEngineWithNoSettings() {
    assertThat(factory.type()).isEqualTo("http");
    assertThat(factory.getConfigSchema()).isEmpty();
    assertThat(factory.describe().canInitiate()).isTrue();
    assertThat(factory.describe().drivers()).contains(Signal.Kind.REVIEW, Signal.Kind.EVENT);
    factory.validate(new EngineConfig(Map.of()));
  }

  @Test
  void signalsReachTheAgentAndItsDirectivesComeOut() {
    final Sinks.Many<Signal> in = Sinks.many().unicast().onBackpressureBuffer();
    final SupervisorEngine engine = engine();
    final AgentHub.Channel channel = hub.channel("agent-1").orElseThrow();
    final List<Signal> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    channel.signals().subscribe(seen::add);
    final Directive.Command command =
        new Directive.Command("k", new PauseWorkflowCommand("e1"), Cause.initiative(), "r", null);

    StepVerifier.create(engine.run(in.asFlux()))
        .then(
            () -> {
              in.tryEmitNext(new Signal.Tick(Instant.EPOCH));
              assertThat(channel.offer(command)).isTrue();
            })
        .expectNext(command)
        .thenCancel()
        .verify(Duration.ofSeconds(5));
    assertThat(seen).containsExactly(new Signal.Tick(Instant.EPOCH));
    assertThat(channel.reads()).isSameAs(EngineReads.NONE);
  }

  @Test
  void closingTheEngineClosesItsChannel() {
    final SupervisorEngine engine = engine();
    assertThat(hub.channel("agent-1")).isPresent();
    engine.close();
    assertThat(hub.channel("agent-1")).isEmpty();
  }

  @Test
  void newEngineReplacesTheChannelOfAnEarlierOne() {
    final SupervisorEngine first = engine();
    final AgentHub.Channel firstChannel = hub.channel("agent-1").orElseThrow();
    final SupervisorEngine second = engine();
    assertThat(hub.channel("agent-1").orElseThrow()).isNotSameAs(firstChannel);
    // closing the replaced engine does not remove the new channel
    first.close();
    assertThat(hub.channel("agent-1")).isPresent();
    second.close();
  }
}
