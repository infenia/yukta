// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.worker.WorkerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * A control plane with a small payload cap refuses a larger message from a worker at the transport,
 * before the message is rebuilt.
 */
@NoArgsConstructor
class InboundLimitRefusalTest {

  /** The control plane, with a 1 KiB cap: it accepts at most one frame (16 MiB) from a worker. */
  private static ConfigurableApplicationContext controlPlane;

  /** The worker, with the default cap. */
  private static ConfigurableApplicationContext worker;

  @BeforeAll
  static void start() {
    controlPlane =
        new SpringApplicationBuilder(RemoteNodeTest.ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.workers.token=secret",
                "yukta.workers.allow-insecure=true",
                "yukta.messaging.max-payload-bytes=1024")
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
                "yukta.workers.plugin-types=UPPER")
            .run();
    final WorkerRegistry registry = controlPlane.getBean(WorkerRegistry.class);
    await().atMost(Duration.ofSeconds(20)).until(() -> registry.find("w1").isPresent());
  }

  @AfterAll
  static void stop() {
    worker.close();
    controlPlane.close();
  }

  @Test
  void workerMessageOverTheLimit_isRefused() {
    final Message<?> big = DefaultMessage.create(UUID.randomUUID(), "a".repeat(20 * 1024 * 1024));

    StepVerifier.create(
            controlPlane
                .getBean(WorkerNodeExecutor.class)
                .run("UPPER", "default", Flux.just(big), Map.of())
                .contextWrite(Context.of("executionId", "e1")))
        .expectErrorSatisfies(e ->
            assertThat(e).hasStackTraceContaining("Reassembled payload size went out of allowed"))
        .verify(Duration.ofSeconds(30));
  }
}
