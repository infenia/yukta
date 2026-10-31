// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.decision.DecisionModel;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Tests for the behaviour described by {@link RulesFirstControlPlaneFactory}. */
@NoArgsConstructor
class RulesFirstControlPlaneFactoryTest {

  private static final List<Map<String, Object>> RULES =
      List.of(
          Map.of(
              "id", "pause",
              "when", Map.of("event", "NodeStatusChanged", "status", "FAILURE"),
              "then", Map.of("command", "PauseWorkflow")));

  private static final String RULES_KEY = "rules";

  private static final String MODEL_KEY = "model";

  private static final Map<String, Object> MODEL = Map.of("name", "m");

  private static RulesFirstControlPlaneFactory factoryWith(final DecisionModel model) {
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (model != null) {
      beans.addBean("decision", model);
    }
    return new RulesFirstControlPlaneFactory(beans.getBeanProvider(DecisionModel.class));
  }

  private static SupervisorProfile profile(final Map<String, Object> settings) {
    return new SupervisorProfile("p", RulesFirstControlPlaneFactory.PROFILE_TYPE, settings, false);
  }

  @Test
  void combinesTheRulesAndTheModelFromTheSettings() {
    final var factory = factoryWith(mock(DecisionModel.class));

    assertThat(factory.type()).isEqualTo("rules-first-control-plane");
    assertThat(factory.create(profile(Map.of(RULES_KEY, RULES, MODEL_KEY, MODEL))))
        .isInstanceOf(RulesFirstControlPlane.class);
  }

  @Test
  void bothPartsAreRequired() {
    final var factory = factoryWith(mock(DecisionModel.class));

    assertThatThrownBy(() -> factory.create(profile(Map.of(MODEL_KEY, MODEL))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rules");
    assertThatThrownBy(() -> factory.create(profile(Map.of(RULES_KEY, RULES))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesWithoutDecisionModelOrWithInvalidParts() {
    assertThatThrownBy(
            () -> factoryWith(null).create(profile(Map.of(RULES_KEY, RULES, MODEL_KEY, MODEL))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decision");
    final var factory = factoryWith(mock(DecisionModel.class));
    assertThatThrownBy(
            () -> factory.create(profile(Map.of(RULES_KEY, List.of(Map.of()), MODEL_KEY, MODEL))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(profile(Map.of(RULES_KEY, RULES, MODEL_KEY, Map.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
