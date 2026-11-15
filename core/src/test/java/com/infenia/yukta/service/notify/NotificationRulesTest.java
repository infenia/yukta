// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class NotificationRulesTest {

  private static NotificationRules rules(final Map<String, NotificationRuleSettings> rules) {
    return new NotificationRules(
        new YuktaProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            new YuktaProperties.Notifications(
                true, Duration.ofDays(7), 10, Duration.ZERO, rules, Map.of()),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private static NotificationRuleSettings rule(
      final String category, final List<String> sessions, final List<String> audience) {
    return new NotificationRuleSettings(
        category,
        sessions,
        List.of(),
        audience,
        NotificationSeverity.WARN,
        List.of(),
        Duration.ofMinutes(1));
  }

  @Test
  void withoutRulesOnlyRunFailedIsOnForOperators() {
    final NotificationRules none = rules(Map.of());

    assertThat(none.forRun("run.failed", "s", "w"))
        .hasValueSatisfying(a -> assertThat(a.audience()).containsExactly("role:OPERATOR"));
    assertThat(none.forRun("run.finished", "s", "w")).isEmpty();
    assertThat(none.forRun("run.held", "s", "w")).isEmpty();
  }

  @Test
  void theFirstMatchingRuleByNameWinsAndGlobsUseStarAndQuestionMark() {
    final NotificationRules some =
        rules(
            Map.of(
                "b-all", rule("run.failed", List.of(), List.of("key:b")),
                "a-release", rule("run.failed", List.of("release-?.*"), List.of("key:a"))));

    assertThat(some.forRun("run.failed", "release-1.x", "w").orElseThrow().audience())
        .containsExactly("key:a");
    assertThat(some.forRun("run.failed", "release-12.x", "w").orElseThrow().audience())
        .containsExactly("key:b");
    assertThat(some.forRun("run.failed", "release-1.x", "w").orElseThrow().cooldown())
        .isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void ruleWithoutAnAudienceTellsOperatorsAndAnotherCategoryIsUnaffected() {
    final NotificationRules some =
        rules(Map.of("done", rule("run.finished", List.of(), List.of())));

    assertThat(some.forRun("run.finished", null, null).orElseThrow().audience())
        .containsExactly("role:OPERATOR");
    assertThat(some.forRun("run.failed", "s", "w")).isPresent();
  }

  @Test
  void ruleLimitedToSessionsNeverMatchesRunWithoutOne() {
    final NotificationRules some = rules(Map.of("r", rule("run.failed", List.of("a"), List.of())));

    assertThat(some.forRun("run.failed", null, null)).isEmpty();
    assertThat(some.forRun("run.failed", "b", null)).isEmpty();
  }
}
