// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link RuleControlPlaneFactory}. */
@NoArgsConstructor
class RuleControlPlaneFactoryTest {

  private final RuleControlPlaneFactory factory = new RuleControlPlaneFactory();

  private static SupervisorProfile profile(final Map<String, Object> settings) {
    return new SupervisorProfile("p", RuleControlPlaneFactory.PROFILE_TYPE, settings, false);
  }

  @Test
  void makesRuleControlPlaneFromTheRulesInTheSettings() {
    final Map<String, Object> settings =
        Map.of(
            "rules",
            List.of(
                Map.of(
                    "id", "pause",
                    "when", Map.of("event", "NodeStatusChanged", "status", "FAILURE"),
                    "then", Map.of("command", "PauseWorkflow"))));

    assertThat(factory.type()).isEqualTo("rule-control-plane");
    assertThat(factory.create(profile(settings))).isInstanceOf(RuleControlPlane.class);
  }

  @Test
  void answerRulesNextToCapabilitySettingsAnswerTheRequestOfTheEvent() {
    final Map<String, Object> settings =
        Map.of(
            "canAnswer",
            true,
            "maxRisk",
            "LOW",
            "rules",
            List.of(
                Map.of(
                    "id", "approve",
                    "when", Map.of("event", "InputRequested", "if", "risk == 'LOW'"),
                    "then", Map.of("answer", Map.of("decision", "approve")))));
    final var request =
        new com.infenia.yukta.plugin.input.InputRequest(
            "r1",
            "key",
            "s",
            "wf",
            "e1",
            "ask",
            new com.infenia.yukta.plugin.input.InputRequestSpec(
                "key",
                com.infenia.yukta.plugin.input.InputKind.APPROVAL,
                "Ship?",
                Map.of(),
                List.of(),
                null,
                com.infenia.yukta.plugin.input.InputRisk.LOW,
                com.infenia.yukta.plugin.input.Audience.CURRENT_TIER,
                java.time.Duration.ofMinutes(1),
                com.infenia.yukta.plugin.input.TimeoutAction.FAIL,
                null,
                null,
                "ASK_INPUT"),
            com.infenia.yukta.plugin.input.InputState.OPEN,
            0,
            "auto",
            List.of(),
            null,
            null,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH);

    final var decision =
        java.util.Objects.requireNonNull(
            ((RuleControlPlane) factory.create(profile(settings)))
                .decide(
                    new com.infenia.yukta.plugin.control.ExecutionEvent.InputRequested(
                        request, java.time.Instant.EPOCH))
                .block());

    assertThat(decision.commands())
        .singleElement()
        .isInstanceOf(
            com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand.class);
  }

  @Test
  void invalidRulesAreRefused() {
    assertThatThrownBy(() -> factory.create(profile(Map.of("rulez", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                factory.create(
                    profile(Map.of("rules", List.of(Map.of("when", Map.of("status", "FAILURE")))))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
