// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.decision.DecisionModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Makes model control planes from profiles. The settings take the keys of {@code
 * yukta.control-plane.model.*} ({@link ModelPlaneSettings}), with the same defaults.
 */
@Component
@RequiredArgsConstructor
public class ModelControlPlaneFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "model-control-plane";

  /** The model that answers. */
  private final ObjectProvider<DecisionModel> models;

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    final ModelPlaneSettings settings = ModelPlaneBinder.bind(profile.settings());
    final DecisionModel model = models.getIfAvailable();
    if (model == null) {
      throw new IllegalArgumentException(
          "No decision model is available; enable the decision plugin");
    }
    return new ModelControlPlane(model, settings);
  }
}
