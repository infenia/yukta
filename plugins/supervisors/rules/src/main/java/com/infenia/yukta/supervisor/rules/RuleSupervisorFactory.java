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

  /** The retired types whose settings are already a rule document. */
  private static final Set<String> RULE_TYPES = Set.of("rule-supervisor", "rule-control-plane");

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

  /**
   * Takes over the retired rule types (their settings are a rule document) and the guards, which
   * are presets of this engine now (decision D8).
   *
   * @param legacy the stored supervisor
   * @return the rules supervisor, or empty for a type this engine does not replace
   */
  @Override
  public Optional<Adoption> adopt(final Supervisor legacy) {
    if (RULE_TYPES.contains(legacy.type())) {
      return Optional.of(
          new Adoption(legacy.withEngine(PROFILE_TYPE, legacy.settings()), List.of()));
    }
    final LegacyGuards.Converted converted =
        switch (legacy.type()) {
          case LegacyGuards.FAILURE_GUARD -> LegacyGuards.failureGuard(legacy.settings());
          case LegacyGuards.APPROVAL_GATE -> LegacyGuards.approvalGate(legacy.settings());
          case LegacyGuards.STALL_WATCHDOG -> LegacyGuards.stallWatchdog(legacy.settings());
          default -> null;
        };
    if (converted == null) {
      return Optional.empty();
    }
    final java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>(converted.rules());
    // the common settings (allowedCommands, canAnswer, operatorAccess, ...) stay as they were
    legacy
        .settings()
        .forEach(
            (key, value) -> {
              if (com.infenia.yukta.plugin.control.SupervisorCommonSettings.KEYS.contains(key)) {
                settings.put(key, value);
              }
            });
    return Optional.of(new Adoption(legacy.withEngine(PROFILE_TYPE, settings), converted.notes()));
  }
}
