// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class MetricsPropertiesTest {

  private static MetricsProperties props(
      final Duration interval, final Duration fine, final Duration coarse, final int max) {
    return new MetricsProperties(true, interval, fine, coarse, max, List.of());
  }

  @Test
  void sensibleSettingsAreAccepted() {
    final MetricsProperties ok =
        props(Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(24), 500);

    assertThat(ok.interval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(ok.allowList()).isNotEmpty();
  }

  @Test
  void zeroOrNegativeIntervalStopsStartupInsteadOfKillingTheSampler() {
    assertThatThrownBy(() -> props(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(24), 500))
        .hasMessageContaining("yukta.metrics.interval must be positive");
    assertThatThrownBy(
            () -> props(Duration.ofSeconds(-1), Duration.ofHours(1), Duration.ofHours(24), 500))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(null, Duration.ofHours(1), Duration.ofHours(24), 500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retentionsAndSeriesLimitMustLeaveRoomForSamples() {
    assertThatThrownBy(
            () -> props(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofHours(24), 500))
        .hasMessageContaining("fine-retention");
    assertThatThrownBy(
            () -> props(Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(30), 500))
        .hasMessageContaining("coarse-retention");
    assertThatThrownBy(
            () -> props(Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(24), 0))
        .hasMessageContaining("max-series");
  }
}
