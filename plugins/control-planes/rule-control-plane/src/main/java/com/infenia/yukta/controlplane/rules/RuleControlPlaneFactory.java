// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.rules;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.supervisor.rules.RuleSupervisor;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes rule control planes from profiles. The settings are the rules file as JSON: {@code
 * {"rules": [...]}}, the same format as {@code RuleSupervisor}, next to the capability settings
 * {@code allowedCommands}, {@code canAnswer} and {@code maxRisk}.
 */
@Component
@NoArgsConstructor
public class RuleControlPlaneFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "rule-control-plane";

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    return new RuleControlPlane(
        RuleSupervisor.read(profile.settings()),
        Clock.systemUTC(),
        SupervisorCapabilities.from(profile));
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Rule control plane",
        "A control plane that decides one event at a time with the same rules as the rule"
            + " supervisor, so it can be combined with other control planes.",
        "Use it as a child of a composite when exact rules should decide first, or when the rules"
            + " should also answer input requests.",
        SupervisorCategory.RULES,
        Optional.of(new UiDesign("blue", "rule_settings", "Rule control plane", 140, 80)),
        RuleSupervisor.EXAMPLE);
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return RuleSupervisor.SCHEMA;
  }
}
