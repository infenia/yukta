// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class NotificationsPropertiesTest {

  private static NotificationRuleSettings rule(
      final String category, final List<String> audience, final List<String> channels) {
    return new NotificationRuleSettings(
        category, List.of(), List.of(), audience, null, channels, Duration.ZERO);
  }

  private static YuktaProperties.Notifications with(
      final NotificationRuleSettings rule, final Map<String, Map<String, Map<String, Object>>> ch) {
    return new YuktaProperties.Notifications(
        true, Duration.ofDays(7), 100, Duration.ofSeconds(2), Map.of("my-rule", rule), ch);
  }

  @Test
  void validRuleIsAccepted() {
    final var channels = Map.of("webhook", Map.of("ops", Map.<String, Object>of("url", "x")));

    assertThat(
            with(
                    rule(
                        "run.failed", List.of("role:OPERATOR", "key:arun"), List.of("webhook:ops")),
                    channels)
                .rules())
        .containsKey("my-rule");
  }

  @Test
  void badRuleStopsStartUpNamingTheRule() {
    assertThatThrownBy(() -> with(rule("nope", List.of(), List.of()), Map.of()))
        .hasMessageContaining("rules.my-rule.category");
    assertThatThrownBy(() -> with(rule("run.failed", List.of("supervisor:*"), List.of()), Map.of()))
        .hasMessageContaining("rules.my-rule.audience");
    assertThatThrownBy(() -> with(rule("run.failed", List.of(), List.of("webhook:ops")), Map.of()))
        .hasMessageContaining("rules.my-rule.channels");
  }

  @Test
  void nonPositiveRetentionIsRefused() {
    assertThatThrownBy(() ->
            new YuktaProperties.Notifications(
                true, Duration.ZERO, 1, Duration.ZERO, Map.of(), Map.of()))
        .hasMessageContaining("retention");
  }
}
