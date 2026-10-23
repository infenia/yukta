// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.worker.WorkerInfo;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import reactor.util.context.Context;

/** A worker that stops finishes what it runs and is given no new attempts meanwhile. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class DrainTest {

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
            "yukta.workers.allow-insecure=true",
            "yukta.workers.id=" + name,
            "yukta.workers.plugin-types=UPPER,SLOW",
            "yukta.workers.capacity=2",
            "yukta.workers.lease=3s",
            "yukta.workers.drain-timeout=20s")
        .run();
  }

  private static Flux<Message<?>> run(
      final WorkerNodeExecutor executor, final String type, final String text) {
    return executor
        .run(type, "default", Flux.just(DefaultMessage.create(UUID.randomUUID(), text)), Map.of())
        .contextWrite(Context.of("executionId", "e1", "nodeId", "n1"));
  }

  @Test
  void drainingWorker_finishesItsAttempt_andGetsNoNewOnes() {
    try (ConfigurableApplicationContext controlPlane =
        new SpringApplicationBuilder(ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.workers.token=secret",
                "yukta.workers.allow-insecure=true",
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

        // the slow attempt goes to w1 (equal slots, lowest id)
        final CompletableFuture<Message<?>> slow = run(executor, "SLOW", "x").next().toFuture();
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> first.getBean(NodeRunner.class).running() == 1);

        final CompletableFuture<Void> stopping =
            CompletableFuture.runAsync(() -> first.getBean(WorkerAgent.class).stop());
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> registry.find("w1").map(WorkerInfo::draining).orElse(false));
        assertThat(stopping).isNotDone();

        // meanwhile nothing new goes to w1: its attempt is still running, the new one runs on w2
        assertThat(run(executor, "UPPER", "q").blockFirst().payload()).isEqualTo("Q");
        assertThat(second.getBean(NodeRunner.class).running()).isZero();

        // the draining attempt finishes normally, then the worker is gone
        assertThat(slow.join().payload()).isEqualTo("x");
        stopping.join();
        await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w1").isEmpty());
      }
    }
  }
}
