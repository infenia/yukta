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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

/** A worker in its own JVM is killed with SIGKILL while it runs an attempt. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class ChaosTest {

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

  private static Process startWorker(final String port, final String name) throws IOException {
    final List<String> command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            WorkerMain.class.getName(),
            "yukta.workers.control-plane=localhost:" + port,
            "yukta.workers.token=secret",
            "yukta.workers.allow-insecure=true",
            "yukta.workers.id=" + name,
            "yukta.workers.plugin-types=UPPER,HOLD",
            "yukta.workers.capacity=2",
            "yukta.workers.lease=3s");
    return new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static Flux<Message<?>> run(
      final WorkerNodeExecutor executor, final String type, final String text) {
    return executor
        .run(type, "default", Flux.just(DefaultMessage.create(UUID.randomUUID(), text)), Map.of())
        .contextWrite(Context.of("executionId", "e1", "nodeId", "n1"));
  }

  @Test
  void killedWorker_failsItsAttemptRetryably_andTheRetryRunsOnTheSurvivor() throws IOException {
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
      final Process doomed = startWorker(port, "w1");
      final Process survivor = startWorker(port, "w2");
      try {
        await()
            .atMost(Duration.ofSeconds(60))
            .until(() -> registry.find("w1").isPresent() && registry.find("w2").isPresent());

        // equal slots, lowest id: the held attempt runs on w1
        StepVerifier.create(run(executor, "HOLD", "x"))
            .expectNextCount(1)
            .then(doomed::destroyForcibly)
            .verifyErrorSatisfies(
                error ->
                    assertThat(error)
                        .isInstanceOf(WorkflowExecutionException.class)
                        .hasMessageContaining("w1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> registry.find("w1").isEmpty());
        StepVerifier.create(run(executor, "UPPER", "again"))
            .assertNext(out -> assertThat(out.payload()).isEqualTo("AGAIN"))
            .verifyComplete();
      } finally {
        doomed.destroyForcibly();
        survivor.destroyForcibly();
      }
    }
  }
}
