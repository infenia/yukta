// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.service.worker.WorkerRegistry;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** A control plane and a worker in one JVM, over a real RSocket connection. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource"})
@ExpectedErrorLog(sometimes = "dropped error: .*ClosedChannelException")
class WorkersTransportTest {

  /** The control plane: the workers' registry and the RSocket server on a free port. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {
    @Bean
    /* default */ MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  /** A worker: only the agent. */
  @Configuration
  @EnableAutoConfiguration
  /* default */ static class Worker {
    @Bean
    /* default */ com.infenia.yukta.service.plugin.PluginRegistry plugins() {
      return new com.infenia.yukta.service.plugin.PluginRegistry(
          java.util.List.of(
              new com.infenia.yukta.plugin.type.ProcessorPlugin() {
                @Override
                public String getType() {
                  return "NOOP";
                }

                @Override
                public reactor.core.publisher.Flux<com.infenia.yukta.message.Message<?>> process(
                    final reactor.core.publisher.Flux<com.infenia.yukta.message.Message<?>> input,
                    final java.util.Map<String, Object> config) {
                  return input;
                }
              }));
    }
  }

  private static ConfigurableApplicationContext start(
      final Class<?> config, final String... properties) {
    return new SpringApplicationBuilder(config)
        .web(WebApplicationType.NONE)
        .properties(properties)
        .run();
  }

  @Test
  void workerRegistersOverRsocket_keepsLeaseAndLeavesOnStop() {
    try (ConfigurableApplicationContext controlPlane =
        start(
            ControlPlane.class,
            "spring.rsocket.server.port=0",
            "yukta.workers.token=secret",
            "yukta.workers.allow-insecure=true",
            "yukta.workers.lease=3s")) {
      final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
      final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);

      final ConfigurableApplicationContext worker =
          start(
              Worker.class,
              "yukta.workers.control-plane=localhost:" + port,
              "yukta.workers.token=secret",
              "yukta.workers.allow-insecure=true",
              "yukta.workers.id=w-it",
              "yukta.workers.pools=default,gpu",
              "yukta.workers.plugin-types=HTTP_REQUEST",
              "yukta.workers.capacity=2",
              "yukta.workers.lease=3s");
      await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w-it").isPresent());
      assertThat(registry.find("w-it").orElseThrow().pools())
          .containsExactlyInAnyOrder("default", "gpu");
      assertThat(registry.find("w-it").orElseThrow().capacity()).isEqualTo(2);

      // the lease is renewed well past its 3s
      final var registeredAt = registry.find("w-it").orElseThrow().registeredAt();
      await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8)).until(() -> true);
      assertThat(registry.find("w-it")).isPresent();
      assertThat(registry.find("w-it").orElseThrow().registeredAt()).isEqualTo(registeredAt);

      worker.close();
      await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w-it").isEmpty());
    }
  }

  @Test
  void workerWithWrongToken_isNeverRegistered() {
    try (ConfigurableApplicationContext controlPlane =
        start(
            ControlPlane.class,
            "spring.rsocket.server.port=0",
            "yukta.workers.token=secret",
            "yukta.workers.allow-insecure=true")) {
      final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
      final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);

      try (ConfigurableApplicationContext worker =
          start(
              Worker.class,
              "yukta.workers.control-plane=localhost:" + port,
              "yukta.workers.token=wrong",
              "yukta.workers.id=w-bad")) {
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(registry.find("w-bad")).isEmpty();
        assertThat(worker.isRunning()).isTrue();
      }
    }
  }

  @Test
  void controlPlaneWithoutTls_refusesToStartOnEveryInterface() {
    assertThatThrownBy(
            () ->
                start(
                    ControlPlane.class,
                    "spring.rsocket.server.port=0",
                    "yukta.workers.token=secret"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("plain TCP");
  }

  @Test
  void addressWithoutPort_stopsTheWorkerFromStarting() {
    assertThatThrownBy(
            () ->
                start(
                    Worker.class,
                    "yukta.workers.control-plane=localhost",
                    "yukta.workers.token=secret",
                    "yukta.workers.allow-insecure=true",
                    "yukta.workers.id=w"))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
