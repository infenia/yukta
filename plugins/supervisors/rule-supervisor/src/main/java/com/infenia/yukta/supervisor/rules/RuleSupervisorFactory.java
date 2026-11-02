// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Clock;
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

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "rule-supervisor";

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    return new RuleSupervisor(
        RuleSupervisor.read(profile.settings()),
        Clock.systemUTC(),
        SupervisorCapabilities.from(profile));
  }
}
