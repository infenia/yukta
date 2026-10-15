// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** A worker that knows several control planes ends up on the leader. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class FollowTheLeaderTest {

  /** A control plane whose leadership the test decides. */
  @Configuration
  @EnableAutoConfiguration
  @Import(WorkerRegistry.class)
  /* default */ static class ControlPlane {
    /** Whether this control plane is the leader. */
    /* default */ final AtomicBoolean leader = new AtomicBoolean();

    @Bean
    /* default */ MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }

    @Bean
    /* default */ LeaderElection election() {
      return leader::get;
    }
  }

  private static ConfigurableApplicationContext controlPlane(final boolean leader) {
    final ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ControlPlane.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.rsocket.server.port=0",
                "yukta.ha.mode=postgres",
                "yukta.workers.token=secret",
                "yukta.workers.lease=3s")
            .run();
    context.getBean(ControlPlane.class).leader.set(leader);
    return context;
  }

  private static String port(final ConfigurableApplicationContext context) {
    return context.getEnvironment().getProperty("local.rsocket.server.port");
  }

  @Test
  void workerTriesTheControlPlanesInTurn_andStaysWithTheLeader() {
    try (ConfigurableApplicationContext standby = controlPlane(false);
        ConfigurableApplicationContext leader = controlPlane(true)) {
      // the first address does not answer at all, the second is a standby, the third is the leader
      try (ConfigurableApplicationContext worker =
          new SpringApplicationBuilder(RemoteNodeWorkerConfig.class)
              .web(WebApplicationType.NONE)
              .properties(
                  "yukta.workers.control-plane=localhost:1,,localhost:"
                      + port(standby)
                      + ", localhost:"
                      + port(leader),
                  "yukta.workers.token=secret",
                  "yukta.workers.id=w1",
                  "yukta.workers.plugin-types=UPPER",
                  "yukta.workers.lease=3s")
              .run()) {
        await()
            .atMost(Duration.ofSeconds(60))
            .until(() -> leader.getBean(WorkerRegistry.class).find("w1").isPresent());

        assertThat(standby.getBean(WorkerRegistry.class).list()).isEmpty();
        assertThat(worker.isRunning()).isTrue();
      }
    }
  }
}
