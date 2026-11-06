// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.decision.DecisionModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Makes model control planes from profiles. The settings take the keys of {@code
 * yukta.control-plane.model.*} ({@link ModelPlaneSettings}), with the same defaults, next to the
 * capability settings {@code allowedCommands}, {@code canAnswer} and {@code maxRisk}, which decide
 * whether it answers input requests or only advises.
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
    return new ModelControlPlane(model, settings, SupervisorCapabilities.from(profile));
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Model control plane",
        "A control plane that asks a decision model what to do about each event: a System One model"
            + " or any chat model. It can advise on or answer input requests when allowed.",
        "Use it when the situations are easier to describe in words than in rules.",
        SupervisorCategory.MODEL,
        Optional.of(new UiDesign("violet", "psychology", "Model control plane", 140, 80)),
        Map.of(
            "name", "tev1:0.8b", "events", List.of("NodeStatusChanged", "WorkflowStatusChanged")));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            "backend", false, "SYSTEMONE", "How the model is called", "SYSTEMONE", "LLM"),
        ConfigField.optional(
            "provider", Type.STRING, null, "For backend LLM: openai, anthropic or ollama"),
        ConfigField.required("name", Type.STRING, "The model name"),
        ConfigField.optional(
            "base-url", Type.STRING, null, "The endpoint; a local Ollama for System One"),
        ConfigField.optional(
            "api-key", Type.SECRET, null, "The API key, if the endpoint needs one"),
        ConfigField.optional(
            "timeout", Type.STRING, "2s", "How long to wait for an answer, such as 2s"),
        ConfigField.optional(
            "min-probability",
            Type.NUMBER,
            0.6,
            "A choice acts only when rated at least this likely"),
        ConfigField.optional(
            "min-confidence",
            Type.NUMBER,
            0.8,
            "An input request is answered only at this confidence, from 0 to 1"),
        ConfigField.optional("instructions", Type.STRING, null, "The question put to the model"),
        ConfigField.optional(
            "options",
            Type.MAP,
            null,
            "The answers the model may give, each with a description and a command (NONE, PAUSE,"
                + " STOP); at least two"),
        ConfigField.optional("events", Type.LIST, null, "The event types to ask about"),
        ConfigField.optional(
            "keep-alive",
            Type.STRING,
            null,
            "For System One: how long Ollama keeps the model loaded"),
        ConfigField.optional("num-ctx", Type.NUMBER, null, "For System One: the context size"),
        ConfigField.optional(
            "max-calls-per-minute",
            Type.NUMBER,
            0,
            "Most model calls per minute; 0 asks about every matching event"));
  }
}
