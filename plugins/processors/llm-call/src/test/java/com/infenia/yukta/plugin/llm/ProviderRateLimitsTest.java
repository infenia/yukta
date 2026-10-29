// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class ProviderRateLimitsTest {

  private final ProviderRateLimits limits = new ProviderRateLimits();

  @Test
  void providerWithoutLimit_neverWaits() {
    assertThat(limits.reserve("OPENAI", 0, Duration.ZERO)).isZero();
    assertThat(limits.reserve("OPENAI", 0, Duration.ZERO)).isZero();
  }

  @Test
  void callsAreSpacedEvenly_perProvider_andTheLimitIsCaseInsensitive() {
    limits.configure(Map.of("openai", 60)); // one per second
    final long second = TimeUnit.SECONDS.toNanos(1);

    assertThat(limits.reserve("OPENAI", 0, Duration.ofSeconds(10))).isZero();
    assertThat(limits.reserve("OPENAI", 0, Duration.ofSeconds(10))).isEqualTo(second);
    assertThat(limits.reserve("OPENAI", 0, Duration.ofSeconds(10))).isEqualTo(2 * second);
    // another provider has its own turn
    assertThat(limits.reserve("ANTHROPIC", 0, Duration.ZERO)).isZero();
    // later, the queue has drained
    assertThat(limits.reserve("OPENAI", 10 * second, Duration.ZERO)).isZero();
  }

  @Test
  void turnFurtherAwayThanCallMayTake_isRefused_andKeepsNoPlace() {
    limits.configure(Map.of("openai", 1)); // one per minute
    limits.reserve("OPENAI", 0, Duration.ofSeconds(1));

    assertThatThrownBy(() -> limits.reserve("OPENAI", 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Rate limit of OPENAI");
    // the refused call did not push the queue back: a patient call still gets the next minute
    assertThat(limits.reserve("OPENAI", 0, Duration.ofMinutes(2)))
        .isEqualTo(TimeUnit.MINUTES.toNanos(1));
  }

  @Test
  void zeroOrNegativeOrMissingMeansNoLimit() {
    final Map<String, Integer> configured = new HashMap<>();
    configured.put("openai", 0);
    configured.put("anthropic", null);
    limits.configure(configured);
    assertThat(limits.reserve("ANTHROPIC", 0, Duration.ZERO)).isZero();
    assertThat(limits.reserve("OPENAI", 0, Duration.ZERO)).isZero();
  }
}
