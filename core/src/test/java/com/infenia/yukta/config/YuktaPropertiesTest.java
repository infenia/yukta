// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

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
    assertThat(properties.controlBus().batchTimeout()).isEqualTo(Duration.ofMillis(50));
    assertThat(properties.cache().ttl()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void outOfRangeValueStopsStartup() {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    runner
        .withPropertyValues("yukta.execution.max-concurrent=-5")
        .run(context -> failure.set(context.getStartupFailure()));

    assertThat(failure.get()).hasStackTraceContaining("execution.maxConcurrent");
  }

  @Test
  void theOldNamesStillWork() {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty("control.bus.batch.timeout.ms", "75")
            .withProperty("workflow.cache.ttl.ms", "1000")
            .withProperty("control.bus.batch.size", "7");
    new LegacyPropertyAliases().postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("yukta.control-bus.batch-timeout")).isEqualTo("75ms");
    assertThat(environment.getProperty("yukta.cache.ttl")).isEqualTo("1000ms");
    assertThat(environment.getProperty("yukta.control-bus.batch-size")).isEqualTo("7");
  }
}
