// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Runs node attempts on a worker over a real RSocket connection. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
class RemoteNodeTest {

  /** Counts heartbeats the control plane received. */
  private static final AtomicInteger BEATS = new AtomicInteger();

  /** The control plane. */
  private static ConfigurableApplicationContext controlPlane;

  /** The worker. */
  private static ConfigurableApplicationContext worker;

  /** Runs attempts on the worker. */
  private static WorkerNodeExecutor executor;

  /** The control plane: the registry, the RSocket server and the executor. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {
    @Bean
    /* default */ MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  @BeforeAll
  static void start() {
    controlPlane =
        new SpringApplicationBuilder(ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.workers.token=secret",
                "yukta.workers.allow-insecure=true",
                "yukta.workers.lease=3s")
            .run();
    final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
    worker =
        new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
            .web(WebApplicationType.NONE)
            .properties(
                "yukta.workers.control-plane=localhost:" + port,
                "yukta.workers.token=secret",
                "yukta.workers.allow-insecure=true",
                "yukta.workers.id=w1",
                "yukta.workers.pools=default",
                "yukta.workers.plugin-types=UPPER,BOOM,BAD,ABORT,HEART,CTX,HOLD,NOT_PROCESSOR",
                "yukta.workers.capacity=1",
                "yukta.workers.lease=3s")
            .run();
    final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);
    await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w1").isPresent());
    executor = controlPlane.getBean(WorkerNodeExecutor.class);
  }

  @AfterAll
  static void stop() {
    worker.close();
    controlPlane.close();
  }

  /**
   * The worker reports its used slots on its heartbeat, so a slot freed by the previous attempt is
   * still counted as busy until the next one. Wait for the report before the next attempt, in a
   * test as well as between tests.
   */
  @BeforeEach
  void awaitFreeSlot() {
    final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> registry.find("w1").map(info -> info.usedSlots() == 0).orElse(false));
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private static Flux<Message<?>> run(final String type, final Flux<Message<?>> input) {
    return executor
        .run(type, "default", input, Map.of("k", "v"))
        .contextWrite(
            Context.of(
                "executionId",
                "e1",
                "nodeId",
                "n1",
                WorkflowContext.HEARTBEAT_KEY,
                (Runnable) BEATS::incrementAndGet));
  }

  @Test
  void attemptRunsOnWorker_withTheContext() {
    StepVerifier.create(run("UPPER", Flux.just(message("a"), message("b"))))
        .assertNext(out -> assertThat(out.payload()).isEqualTo("A"))
        .assertNext(out -> assertThat(out.payload()).isEqualTo("B"))
        .verifyComplete();
    awaitFreeSlot();
    StepVerifier.create(run("CTX", Flux.empty()))
        .assertNext(out -> assertThat(out.payload()).isEqualTo("e1/n1/v"))
        .verifyComplete();
  }

  @Test
  void workerFailure_comesBackAndBadInputKeepsItsMeaning() {
    StepVerifier.create(run("BOOM", Flux.empty()))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessageContaining("IllegalStateException")
                    .hasMessageContaining("boom"));
    awaitFreeSlot();
    StepVerifier.create(run("BAD", Flux.empty()))
        .verifyErrorSatisfies(
            e ->
                assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessage("bad input"));
  }

  @Test
  void hardStop_comesBackAsRunAborted_soTheEngineEndsTheRun() {
    StepVerifier.create(run("ABORT", Flux.empty()))
        .verifyErrorSatisfies(
            e -> {
              assertThat(RunAbortedException.isAbort(e)).isTrue();
              assertThat(e).hasMessage("budget");
            });
  }

  @Test
  void pluginHeartbeat_reachesTheControlPlane() {
    final int before = BEATS.get();

    StepVerifier.create(run("HEART", Flux.just(message("x")))).expectNextCount(1).verifyComplete();

    assertThat(BEATS.get()).isGreaterThan(before);
  }

  @Test
  void cancellingTheCall_cancelsThePluginOnTheWorker_andFreesTheSlot() {
    final int before = RemoteNodeWorkerConfig.CANCELLED.get();

    StepVerifier.create(run("HOLD", Flux.just(message("x"))))
        .expectNextCount(1)
        .thenCancel()
        .verify();

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> RemoteNodeWorkerConfig.CANCELLED.get() > before);
    awaitFreeSlot();
    StepVerifier.create(run("UPPER", Flux.just(message("again"))))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void withNoFreeSlot_orNoWorkerThatMayRunTheType_theAttemptFailsAtOnce() {
    final reactor.core.Disposable running = run("HOLD", Flux.just(message("x"))).subscribe();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                StepVerifier.create(run("UPPER", Flux.just(message("y"))))
                    .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("right now")));
    running.dispose();

    StepVerifier.create(run("NOT_ALLOWED", Flux.empty()))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("No connected worker"));
    StepVerifier.create(executor.run("UPPER", "other-pool", Flux.empty(), Map.of()))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("other-pool"));
    assertThat(Mono.just(1).block()).isEqualTo(1);
  }

  @Test
  void noHeartbeatInContext_attemptStillRuns() {
    StepVerifier.create(
            executor
                .run("UPPER", "default", Flux.just(message("q")), Map.of())
                .contextWrite(Context.of("executionId", "e2")))
        .assertNext(out -> assertThat(out.payload()).isEqualTo("Q"))
        .verifyComplete();
  }
}
