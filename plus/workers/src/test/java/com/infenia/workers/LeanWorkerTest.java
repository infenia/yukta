// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.worker.WorkerRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** A process with a control plane address starts only what a worker needs. */
@NoArgsConstructor
class LeanWorkerTest {

  /** The control plane. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {}

  @Test
  void workerHasNoServerPersistenceOrMcpAndStillRunsPlacedNode() {
    try (ConfigurableApplicationContext controlPlane =
        new SpringApplicationBuilder(ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.workers.token=secret",
                "yukta.workers.allow-insecure=true")
            .run()) {
      final String port = controlPlane.getEnvironment().getProperty("local.rsocket.server.port");
      // no web() call: the role decides the web application type
      try (ConfigurableApplicationContext worker =
          new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
              .properties(
                  "yukta.workers.control-plane=localhost:" + port,
                  "yukta.workers.token=secret",
                  "yukta.workers.allow-insecure=true",
                  "yukta.workers.id=lean",
                  "yukta.workers.plugin-types=UPPER")
              .run()) {
        assertThat(worker.getBeansOfType(NodeRunner.class)).hasSize(1);
        assertThat(Arrays.asList(worker.getBeanDefinitionNames()))
            .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("r2dbc"))
            .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("flyway"))
            .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("mcpserver"));
        assertThat(worker.getEnvironment().getProperty("spring.main.web-application-type"))
            .isEqualTo("none");
        final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);
        await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("lean").isPresent());
        final Message<?> in = DefaultMessage.create(UUID.randomUUID(), "a");
        StepVerifier.create(
                controlPlane
                    .getBean(WorkerNodeExecutor.class)
                    .run("UPPER", "default", Flux.just(in), Map.of())
                    .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
            .assertNext(out -> assertThat(out.payload()).isEqualTo("A"))
            .verifyComplete();
      }
    }
  }
}
