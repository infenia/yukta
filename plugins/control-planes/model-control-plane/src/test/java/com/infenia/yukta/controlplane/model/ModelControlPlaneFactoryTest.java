// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.decision.ModelSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Tests for {@link ModelControlPlaneFactory} and {@link ModelPlaneBinder}. */
@NoArgsConstructor
class ModelControlPlaneFactoryTest {

  private static final String NAME = "name";

  private static ModelControlPlaneFactory factoryWith(final DecisionModel model) {
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (model != null) {
      beans.addBean("model", model);
    }
    return new ModelControlPlaneFactory(beans.getBeanProvider(DecisionModel.class));
  }

  private static SupervisorProfile profile(final Map<String, Object> settings) {
    return new SupervisorProfile("p", ModelControlPlaneFactory.PROFILE_TYPE, settings, false);
  }

  @Test
  void bindsTheSameKeysAndDefaultsAsTheProperties() {
    final ModelPlaneSettings settings =
        ModelPlaneBinder.bind(
            Map.of(
                NAME,
                "tev1:0.8b",
                "min-probability",
                0.7,
                "events",
                List.of("LogLine"),
                "options",
                Map.of(
                    "ok", Map.of("description", "fine", "command", "NONE"),
                    "halt", Map.of("description", "bad", "command", "STOP"))));

    assertThat(settings.name()).isEqualTo("tev1:0.8b");
    assertThat(settings.backend()).isEqualTo(ModelSpec.Backend.SYSTEMONE);
    assertThat(settings.minProbability()).isEqualTo(0.7);
    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(settings.events()).containsExactly("LogLine");
    assertThat(settings.options().get("halt").command()).isEqualTo(ModelPlaneSettings.Command.STOP);
  }

  @Test
  void camelCaseKeysAndDefaultOptionsWork() {
    final ModelPlaneSettings settings =
        ModelPlaneBinder.bind(Map.of(NAME, "m", "minProbability", 0.9, "numCtx", 2048));

    assertThat(settings.minProbability()).isEqualTo(0.9);
    assertThat(settings.numCtx()).isEqualTo(2048);
    assertThat(settings.options()).containsKeys("continue", "pause");
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void nullSettingsAreTreatedAsUnset() {
    final Map<String, Object> settings = new java.util.HashMap<>();
    settings.put(NAME, "m");
    settings.put("api-key", null);

    assertThat(ModelPlaneBinder.bind(settings).apiKey()).isNull();
  }

  @Test
  void badSettingsAreReportedWithoutTheirValues() {
    assertThatThrownBy(() -> ModelPlaneBinder.bind(Map.of(NAME, "m", "min-probability", "s3cret")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("min-probability")
        .hasMessageNotContaining("s3cret");
    assertThatThrownBy(() -> ModelPlaneBinder.bind(Map.of("backend", "LLM", NAME, "m")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provider");
    assertThatThrownBy(() -> ModelPlaneBinder.bind(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void makesControlPlaneWhenModelIsAvailable() {
    final var plane = factoryWith(mock(DecisionModel.class)).create(profile(Map.of(NAME, "m")));

    assertThat(plane).isInstanceOf(ModelControlPlane.class);
    assertThat(factoryWith(null).type()).isEqualTo("model-control-plane");
  }

  @Test
  void refusesWithoutDecisionModel() {
    assertThatThrownBy(() -> factoryWith(null).create(profile(Map.of(NAME, "m"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decision");
  }
}
