// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TrackerPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Configuration
  @EnableConfigurationProperties(TrackerProperties.class)
  /* default */ static class Config {}

  @Test
  void defaultsApplyWhenNothingIsSet() {
    final AtomicReference<TrackerProperties> bound = new AtomicReference<>();
    runner.run(context -> bound.set(context.getBean(TrackerProperties.class)));

    assertThat(bound.get().sessionTtl()).isNull();
    assertThat(bound.get().maxSessions()).isZero();
    assertThat(bound.get().cleanupTtl()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void settingsAreBound() {
    final AtomicReference<TrackerProperties> bound = new AtomicReference<>();
    runner
        .withPropertyValues(
            "yukta.tracker.session-ttl=24h",
            "yukta.tracker.max-sessions=5",
            "yukta.tracker.cleanup-ttl=30s")
        .run(context -> bound.set(context.getBean(TrackerProperties.class)));

    assertThat(bound.get())
        .isEqualTo(new TrackerProperties(Duration.ofHours(24), 5, Duration.ofSeconds(30)));
  }

  @Test
  void negativeSessionCountStopsStartup() {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    runner
        .withPropertyValues("yukta.tracker.max-sessions=-1")
        .run(context -> failure.set(context.getStartupFailure()));

    assertThat(failure.get()).isNotNull();
  }
}
