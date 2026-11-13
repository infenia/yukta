// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorPreset;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.UiDesign;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes rule supervisors from profiles. The settings are the rules file as JSON: {@code {"rules":
 * [...]}}, read as strictly as the file is, next to the capability settings {@code
 * allowedCommands}, {@code canAnswer} and {@code maxRisk}.
 */
@Component
@NoArgsConstructor
public class RuleSupervisorFactory implements SupervisorFactory {

  /** The engine type. */
  public static final String PROFILE_TYPE = "rules";

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor profile) {
    return new RuleSupervisor(
        RuleSupervisor.read(profile.settings()),
        Clock.systemUTC(),
        SupervisorCapabilities.from(profile));
  }

  @Override
  public Set<com.infenia.yukta.plugin.control.engine.Signal.Kind> signals() {
    return Set.of(
        com.infenia.yukta.plugin.control.engine.Signal.Kind.EVENT,
        com.infenia.yukta.plugin.control.engine.Signal.Kind.TICK,
        com.infenia.yukta.plugin.control.engine.Signal.Kind.REVIEW,
        com.infenia.yukta.plugin.control.engine.Signal.Kind.DELEGATED);
  }

  @Override
  public boolean canInitiate() {
    return true;
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Rules",
        "Reacts to events and timers with rules written in JSON: when an event matches, it sends a"
            + " command, answers an input request or refuses a command.",
        "Use it for exact, repeatable cases such as pausing a workflow after repeated failures or"
            + " refusing a dangerous stop.",
        SupervisorCategory.RULES,
        Optional.of(new UiDesign("blue", "rule", "Rules", 140, 80)),
        RuleSupervisor.EXAMPLE);
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return RuleSupervisor.SCHEMA;
  }

  @Override
  public List<SupervisorPreset> presets() {
    return RulePresets.ALL;
  }
}
