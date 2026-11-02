// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import com.infenia.yukta.controlplane.model.ModelControlPlane;
import com.infenia.yukta.controlplane.model.ModelPlaneBinder;
import com.infenia.yukta.controlplane.rules.RuleControlPlane;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
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

/**
 * Makes rules-first control planes from profiles. The settings hold {@code rules}, the list of
 * rules, and {@code model}, the keys of {@code yukta.control-plane.model.*}, next to the capability
 * settings {@code allowedCommands}, {@code canAnswer} and {@code maxRisk}, which apply to both: the
 * rules and the model answer input requests only when the profile allows it.
 */
@Component
@RequiredArgsConstructor
public class RulesFirstControlPlaneFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "rules-first-control-plane";

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
    final SupervisorCapabilities capabilities = SupervisorCapabilities.from(profile);
    return new RulesFirstControlPlane(
        new RuleControlPlane(
            RuleSupervisor.read(Map.of("rules", rules)), Clock.systemUTC(), capabilities),
        new ModelControlPlane(
            decisionModel, ModelPlaneBinder.bind((Map<String, Object>) model), capabilities));
  }
}
