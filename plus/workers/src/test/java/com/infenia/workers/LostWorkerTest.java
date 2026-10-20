// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** What happens to an attempt when its worker goes away, and where the retry lands. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class LostWorkerTest {

  /** The control plane. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {
    @Bean
    /* default */ MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  private static ConfigurableApplicationContext worker(final String port, final String name) {
    return new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
        .web(WebApplicationType.NONE)
        .properties(
            "yukta.workers.control-plane=localhost:" + port,
            "yukta.workers.token=secret",
            "yukta.workers.id=" + name,
            "yukta.workers.plugin-types=UPPER,HOLD,SLOW",
            "yukta.workers.capacity=2",
            "yukta.workers.lease=3s")
        .run();
  }

  private static Flux<Message<?>> run(
      final WorkerNodeExecutor executor, final String type, final String text) {
    return executor
        .run(type, "default", Flux.just(DefaultMessage.create(UUID.randomUUID(), text)), Map.of())
        .contextWrite(Context.of("executionId", "e1", "nodeId", "n1"));
  }

  @Test
  void workerDyingMidAttempt_failsRetryably_retryRunsOnOtherWorker() {
    try (ConfigurableApplicationContext controlPlane =
        new SpringApplicationBuilder(ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.workers.token=secret",
                "yukta.workers.lease=3s")
            .run()) {
      final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
      final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);
      final WorkerNodeExecutor executor = controlPlane.getBean(WorkerNodeExecutor.class);
      try (ConfigurableApplicationContext first = worker(port, "w1");
          ConfigurableApplicationContext second = worker(port, "w2")) {
        assertThat(second.isRunning()).isTrue();
        await()
            .atMost(Duration.ofSeconds(20))
            .until(() -> registry.find("w1").isPresent() && registry.find("w2").isPresent());

        // the first attempt goes to w1 (equal slots, lowest id) and is held there
        StepVerifier.create(run(executor, "HOLD", "x"))
            .expectNextCount(1)
            .then(() -> first.getBean(WorkerAgent.class).stop())
            .verifyErrorSatisfies(
                error ->
                    assertThat(error)
                        .isInstanceOf(WorkflowExecutionException.class)
                        .hasMessageContaining("w1"));

        await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w1").isEmpty());
        StepVerifier.create(run(executor, "UPPER", "again"))
            .assertNext(out -> assertThat(out.payload()).isEqualTo("AGAIN"))
            .verifyComplete();
      }
    }
  }
}
