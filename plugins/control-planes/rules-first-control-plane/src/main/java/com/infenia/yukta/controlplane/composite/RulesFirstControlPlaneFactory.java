// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import com.infenia.yukta.controlplane.model.ModelControlPlane;
import com.infenia.yukta.controlplane.model.ModelPlaneBinder;
import com.infenia.yukta.controlplane.rules.RuleControlPlane;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.supervisor.rules.RuleSupervisor;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes rules-first control planes from profiles. The settings hold {@code rules}, the list of
 * rules, and {@code model}, the keys of {@code yukta.control-plane.model.*}.
 */
@Component
@RequiredArgsConstructor
public class RulesFirstControlPlaneFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "rules-first-control-plane";

  /** Writes the rules back to the JSON the rule reader takes. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The model that answers when no rule fired. */
  private final ObjectProvider<DecisionModel> models;

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  @SuppressWarnings("unchecked")
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    final Object rules = profile.settings().get("rules");
    final Object model = profile.settings().get("model");
    if (!(rules instanceof List<?>) || !(model instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(
          "Settings need 'rules' (a list of rules) and 'model' (the model settings)");
    }
    final DecisionModel decisionModel = models.getIfAvailable();
    if (decisionModel == null) {
      throw new IllegalArgumentException(
          "No decision model is available; enable the decision plugin");
    }
    return new RulesFirstControlPlane(
        new RuleControlPlane(
            RuleSupervisor.read(JSON.writeValueAsString(Map.of("rules", rules))),
            Clock.systemUTC()),
        new ModelControlPlane(decisionModel, ModelPlaneBinder.bind((Map<String, Object>) model)));
  }
}
