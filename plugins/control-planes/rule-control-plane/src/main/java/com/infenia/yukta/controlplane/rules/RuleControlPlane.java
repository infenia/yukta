// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.rules;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.supervisor.rules.RuleSet;
import com.infenia.yukta.supervisor.rules.RuleSupervisor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The rule-based control plane: the rules of {@link RuleSupervisor} (same JSON format) behind the
 * {@link ControlPlanePlugin} contract. Enabled by setting {@code yukta.control-plane.rules.file}.
 */
@Component
@ConditionalOnProperty("yukta.control-plane.rules.file")
public final class RuleControlPlane implements ControlPlanePlugin {

  /** Identifies this plugin in the audit log. */
  public static final String PLUGIN_ID = "rule-control-plane";

  /** The rule engine. */
  private final RuleSupervisor rules;

  /**
   * Loads the rules from a file.
   *
   * @param file the JSON rules file
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if a rule is invalid
   */
  @Autowired
  public RuleControlPlane(@Value("${yukta.control-plane.rules.file}") final Path file)
      throws IOException {
    this(RuleSupervisor.read(Files.readString(file)), Clock.systemUTC());
  }

  /**
   * Creates the plugin from parsed rules.
   *
   * @param ruleSet the rules
   * @param clock the time source
   * @throws IllegalArgumentException if a rule is invalid
   */
  public RuleControlPlane(final RuleSet ruleSet, final Clock clock) {
    this.rules = new RuleSupervisor(ruleSet, clock);
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return PLUGIN_ID;
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    return rules
        .supervise(Flux.just(event))
        .collectList()
        .map(commands -> new ControlDecision(commands, 1, Source.RULE));
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    return rules.review(request);
  }
}
