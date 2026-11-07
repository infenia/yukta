// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class YuktaPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Config.class);

  @Configuration
  @EnableConfigurationProperties(YuktaProperties.class)
  /* default */ static class Config {}

  @Test
  void defaultsApplyWhenNothingIsSet() {
    final AtomicReference<YuktaProperties> bound = new AtomicReference<>();
    runner.run(context -> bound.set(context.getBean(YuktaProperties.class)));

    final YuktaProperties properties = bound.get();
    assertThat(properties.execution().queueCapacity()).isEqualTo(100);
    assertThat(properties.cache().ttl()).isEqualTo(Duration.ofMinutes(10));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "yukta.execution.queue-timeout",
        "yukta.execution.shutdown-timeout",
        "yukta.supervisor.review-timeout",
        "yukta.stop.drain-timeout",
        "yukta.start.idempotency-ttl",
        "yukta.results.retention",
        "yukta.workers.lease",
        "yukta.cache.ttl"
      })
  void negativeDurationStopsStartupInsteadOfSwitchingLimitOff(final String key) {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    runner
        .withPropertyValues(key + "=-5m")
        .run(context -> failure.set(context.getStartupFailure()));

    assertThat(failure.get()).isNotNull();
  }

  @Test
  void outOfRangeValueStopsStartup() {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    runner
        .withPropertyValues("yukta.execution.max-concurrent=-5")
        .run(context -> failure.set(context.getStartupFailure()));

    assertThat(failure.get()).hasStackTraceContaining("execution.maxConcurrent");
  }
}
