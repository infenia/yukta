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
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** The {@code http} engine passes signals to the agent and the agent's directives back. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource", "PMD.TooManyMethods"})
class HttpEngineTest {

  private final AgentHub hub = new AgentHub();
  private final HttpEngineFactory factory =
      new HttpEngineFactory(
          hub,
          new Outbound(
              new Egress(
                  EgressPolicy.OPEN,
                  HostAllowList.from(List.of(InetAddress.getLoopbackAddress().getHostAddress()))),
              WebClient.create(),
              JsonMapper.builder().build(),
              name -> null));

  private SupervisorEngine engine() {
    return engine(Map.of());
  }

  private SupervisorEngine engine(final Map<String, Object> settings) {
    return engine(settings, EngineReads.NONE);
  }

  private SupervisorEngine engine(final Map<String, Object> settings, final EngineReads reads) {
    return factory.create(
        new EngineConfig(settings),
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
            return reads;
          }
        });
  }

  private static EngineReads reading(final String executionId) {
    return new EngineReads() {
      @Override
      public Flux<RunView> executions(final String status, final int limit) {
        return Flux.just(new RunView("s", executionId, "w", "RUNNING", Instant.EPOCH));
      }

      @Override
      public Flux<com.infenia.yukta.plugin.input.InputRequest> openInputs(final int limit) {
        return Flux.empty();
      }
    };
  }

  @Test
  void theFactoryDescribesTheExternalSystemAndItsOutboundSettings() {
    assertThat(factory.type()).isEqualTo("http");
    assertThat(factory.describe().name()).isEqualTo("External system");
    assertThat(factory.getConfigSchema())
        .extracting(com.infenia.yukta.plugin.core.ConfigField::name)
        .containsExactly("url", "headers", "secretHeaders", "timeoutMs", "maxConcurrent");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> factory.validate(new EngineConfig(Map.of("url", "ftp://x"))))
        .hasMessageContaining("http or https");
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
  void secondEngineOfTheSameSupervisorDoesNotCutTheFirstOnesAgentStream() {
    // SupervisorService.start makes one engine per attachment, and every engine of a supervisor
    // opens the channel keyed by the supervisor id, so a supervisor with two attachments has two
    final SupervisorEngine first = engine();
    final java.util.concurrent.atomic.AtomicBoolean ended =
        new java.util.concurrent.atomic.AtomicBoolean();
    hub.channel("agent-1").orElseThrow().signals().doOnComplete(() -> ended.set(true)).subscribe();

    final SupervisorEngine second = engine();

    assertThat(ended).as("the agent stream of the first attachment").isFalse();
    first.close();
    second.close();
  }

  @Test
  void withUrlEachSignalIsAlsoPostedWithItsCursor() throws Exception {
    final List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      final Sinks.Many<Signal> in = Sinks.many().unicast().onBackpressureBuffer();
      final SupervisorEngine engine =
          engine(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/in"));
      StepVerifier.create(engine.run(in.asFlux()))
          .then(() -> in.tryEmitNext(new Signal.Tick(Instant.EPOCH)))
          .thenAwait(Duration.ofMillis(50))
          .then(
              () ->
                  org.awaitility.Awaitility.await()
                      .atMost(Duration.ofSeconds(10))
                      .until(() -> !bodies.isEmpty()))
          .thenCancel()
          .verify(Duration.ofSeconds(15));
      assertThat(bodies.getFirst()).contains("\"cursor\":1").contains("\"kind\":\"TICK\"");
      engine.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void closingTheEngineClosesItsChannel() {
    final SupervisorEngine engine = engine();
    assertThat(hub.channel("agent-1")).isPresent();
    engine.close();
    assertThat(hub.channel("agent-1")).isEmpty();
  }

  @Test
  void enginesOfOneSupervisorShareOneChannelUntilTheLastCloses() {
    final SupervisorEngine first = engine();
    final AgentHub.Channel channel = hub.channel("agent-1").orElseThrow();
    final SupervisorEngine second = engine();

    assertThat(hub.channel("agent-1").orElseThrow()).isSameAs(channel);
    assertThat(channel.engines()).isEqualTo(2);
    first.close();
    assertThat(hub.channel("agent-1")).containsSame(channel);
    assertThat(channel.engines()).isEqualTo(1);
    second.close();
    assertThat(hub.channel("agent-1")).isEmpty();
  }

  @Test
  void directiveReachesOnlyTheEngineThatReadsItsRun() {
    final SupervisorEngine first = engine(Map.of(), reading("e1"));
    final SupervisorEngine second = engine(Map.of(), reading("e2"));
    final AgentHub.Channel channel = hub.channel("agent-1").orElseThrow();
    final List<Directive> firstGot = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Directive> secondGot = new java.util.concurrent.CopyOnWriteArrayList<>();
    final reactor.core.Disposable a = first.run(Flux.never()).subscribe(firstGot::add);
    final reactor.core.Disposable b = second.run(Flux.never()).subscribe(secondGot::add);
    final Directive.Command forSecond =
        new Directive.Command("k", new PauseWorkflowCommand("e2"), Cause.initiative(), "r", null);
    final Directive.Command forNobody =
        new Directive.Command("k2", new PauseWorkflowCommand("e9"), Cause.initiative(), "r", null);

    assertThat(channel.offer(forSecond)).isTrue();
    assertThat(channel.offer(forNobody)).isTrue();

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(secondGot).containsExactly(forSecond);
              assertThat(firstGot).containsExactly(forNobody);
            });
    a.dispose();
    b.dispose();
    first.close();
    second.close();
  }
}
