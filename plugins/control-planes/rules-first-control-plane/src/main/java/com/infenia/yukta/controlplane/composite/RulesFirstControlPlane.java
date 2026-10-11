// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import com.infenia.yukta.controlplane.rules.RuleControlPlane;
import com.infenia.yukta.controlplane.systemone.SystemOneControlPlane;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Rules first, model second. Deterministic rules decide an event; only when no rule has anything to
 * say is the System One model asked. Command review stays with the rules, so a deny rule cannot be
 * overruled by the model.
 *
 * <p>Enabled by {@code yukta.control-plane.rules-first.rules-file} and {@code
 * yukta.control-plane.rules-first.model} (optional: {@code base-url}, {@code min-probability},
 * {@code timeout}, as for {@link SystemOneControlPlane}). It builds both plugins itself, so leave
 * their own properties unset, or they would also run on their own.
 */
@Component
@ConditionalOnProperty("yukta.control-plane.rules-first.model")
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
   * @param builder the web client builder
   * @param rulesFile the JSON rules file
   * @param baseUrl the Ollama server
   * @param modelName a local System One model
   * @param minProbability answers whose chosen option is less probable than this are ignored
   * @param timeout how long to wait for the model
   * @throws IOException if the rules file cannot be read
   */
  @Autowired
  public RulesFirstControlPlane(
      final WebClient.Builder builder,
      @Value("${yukta.control-plane.rules-first.rules-file}") final Path rulesFile,
      @Value("${yukta.control-plane.rules-first.base-url:http://localhost:11434}")
          final String baseUrl,
      @Value("${yukta.control-plane.rules-first.model}") final String modelName,
      @Value("${yukta.control-plane.rules-first.min-probability:0.6}") final double minProbability,
      @Value("${yukta.control-plane.rules-first.timeout:PT2S}") final Duration timeout)
      throws IOException {
    this(
        new RuleControlPlane(rulesFile),
        new SystemOneControlPlane(builder, baseUrl, modelName, minProbability, timeout));
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
