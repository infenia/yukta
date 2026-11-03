// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.config.YuktaProperties;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class InputPropertiesTest {

  private static YuktaProperties.Input input(
      final Duration defaultTtl, final Duration maxTtl, final Duration sweep) {
    return new YuktaProperties.Input(
        defaultTtl, maxTtl, 5, 50, 100, sweep, Duration.ofDays(30), List.of(), java.util.Map.of());
  }

  @Test
  void maximumTtlBelowTheDefaultIsRefusedAtStartUp() {
    assertThatThrownBy(() -> input(Duration.ofHours(2), Duration.ofHours(1), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-ttl");
  }

  @Test
  void zeroRetentionIsRefused() {
    assertThatThrownBy(
            () ->
                new YuktaProperties.Input(
                    Duration.ofHours(1),
                    Duration.ofHours(2),
                    5,
                    50,
                    100,
                    Duration.ofSeconds(1),
                    Duration.ZERO,
                    List.of(),
                    java.util.Map.of()))
        .hasMessageContaining("retention");
  }

  @Test
  void zeroOrNegativeDurationsAreRefused() {
    assertThatThrownBy(() -> input(Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(1)))
        .hasMessageContaining("default-ttl");
    assertThatThrownBy(
            () -> input(Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(-1)))
        .hasMessageContaining("sweep-interval");
  }

  @Test
  void defaultsBindWhenNothingIsConfigured() {
    final YuktaProperties.Input defaults =
        com.infenia.yukta.testsupport.YuktaSettings.bind(java.util.Map.of()).input();

    assertThat(defaults.defaultTtl()).isEqualTo(Duration.ofHours(1));
    assertThat(defaults.maxTtl()).isEqualTo(Duration.ofHours(24));
    assertThat(defaults.maxOpenPerExecution()).isEqualTo(5);
    assertThat(defaults.maxOpenPerSession()).isEqualTo(50);
    assertThat(defaults.memoryMax()).isEqualTo(10_000);
    assertThat(defaults.sweepInterval()).isEqualTo(Duration.ofSeconds(1));
    assertThat(defaults.redactKeys()).isEmpty();
  }
}
