// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import com.infenia.yukta.controlplane.model.ModelControlPlane;
import com.infenia.yukta.controlplane.model.ModelPlaneSettings;
import com.infenia.yukta.controlplane.rules.RuleControlPlane;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.decision.DecisionModel;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Rules first, model second. Deterministic rules decide an event; only when no rule has anything to
 * say is the model asked. Command review stays with the rules, so a deny rule cannot be overruled
 * by the model.
 *
 * <p>Enabled by {@code yukta.control-plane.rules-first.rules-file} and {@code
 * yukta.control-plane.rules-first.model.name}; the model, its prompt and options are configured
 * below {@code yukta.control-plane.rules-first.model} as described in {@link ModelPlaneSettings}.
 * It builds both plugins itself, so leave the {@code rules} and {@code model} control-plane
 * properties unset, or they would also run on their own.
 */
@Component
@EnableConfigurationProperties(RulesFirstSettings.class)
@ConditionalOnProperty("yukta.control-plane.rules-first.model.name")
public final class RulesFirstControlPlane implements ControlPlanePlugin {

  /** Identifies this plugin in the audit log. */
  public static final String PLUGIN_ID = "rules-first-control-plane";

  /** Decides first. */
  private final ControlPlanePlugin rules;

  /** Asked when the rules have no opinion. */
  private final ControlPlanePlugin model;

  /**
   * Builds both plugins from configuration.
   *
   * @param decisionModel asks the model
   * @param settings the configuration
   * @throws IOException if the rules file cannot be read
   */
  @Autowired
  public RulesFirstControlPlane(
      final DecisionModel decisionModel, final RulesFirstSettings settings) throws IOException {
    this(
        new RuleControlPlane(settings.rulesFile()),
        new ModelControlPlane(decisionModel, settings.model()));
  }

  /**
   * Combines two plugins.
   *
   * @param rules decides first
   * @param model asked when the rules have no opinion
   */
  public RulesFirstControlPlane(final ControlPlanePlugin rules, final ControlPlanePlugin model) {
    this.rules = rules;
    this.model = model;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return PLUGIN_ID;
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    return rules
        .decide(event)
        .onErrorResume(_ -> Mono.empty())
        .defaultIfEmpty(ControlDecision.none(ControlDecision.Source.RULE))
        .flatMap(
            decision -> decision.commands().isEmpty() ? model.decide(event) : Mono.just(decision));
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    return rules.review(request);
  }
}
