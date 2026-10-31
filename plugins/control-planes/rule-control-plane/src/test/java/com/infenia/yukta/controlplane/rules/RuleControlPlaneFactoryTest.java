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
