// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link RuleSupervisorFactory}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RuleSupervisorFactoryTest {

  private final RuleSupervisorFactory factory = new RuleSupervisorFactory();

  @Test
  void makesRuleSupervisorFromTheRulesInTheSettings() {
    final Map<String, Object> settings =
        Map.of(
            "rules",
            List.of(
                Map.of(
                    "id", "pause",
                    "when", Map.of("event", "NodeStatusChanged", "status", "FAILURE"),
                    "then", Map.of("command", "PauseWorkflow"))));

    final ExecutionSupervisor supervisor =
        factory.create(
            new SupervisorProfile("p", RuleSupervisorFactory.PROFILE_TYPE, settings, false));

    assertThat(factory.type()).isEqualTo("rule-supervisor");
    assertThat(supervisor).isInstanceOf(RuleSupervisor.class);
  }

  @Test
  void capabilitySettingsSitNextToTheRulesAndSilenceTheAnswerWarning() {
    final List<Object> rules =
        List.of(
            Map.of(
                "id", "approve",
                "when", Map.of("event", "InputRequested"),
                "then", Map.of("answer", Map.of("decision", "approve"))));

    final RuleSupervisor allowed =
        (RuleSupervisor)
            factory.create(
                new SupervisorProfile(
                    "p",
                    RuleSupervisorFactory.PROFILE_TYPE,
                    Map.of("rules", rules, "canAnswer", true, "maxRisk", "MEDIUM"),
                    false));
    final RuleSupervisor notAllowed =
        (RuleSupervisor)
            factory.create(
                new SupervisorProfile(
                    "p",
                    RuleSupervisorFactory.PROFILE_TYPE,
                    Map.of("rules", rules, "allowedCommands", List.of("EscalateInputCommand")),
                    false));

    assertThat(allowed.warning()).isEmpty();
    assertThat(notAllowed.warning()).isPresent();
  }

  @Test
  void invalidRuleOrMisspelledFieldIsRefused() {
    final SupervisorProfile misspelled =
        new SupervisorProfile(
            "p", RuleSupervisorFactory.PROFILE_TYPE, Map.of("rulez", List.of()), false);
    final SupervisorProfile noId =
        new SupervisorProfile(
            "p",
            RuleSupervisorFactory.PROFILE_TYPE,
            Map.of("rules", List.of(Map.of("when", Map.of("status", "FAILURE")))),
            false);

    assertThatThrownBy(() -> factory.create(misspelled))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(noId)).isInstanceOf(IllegalArgumentException.class);
  }
}
