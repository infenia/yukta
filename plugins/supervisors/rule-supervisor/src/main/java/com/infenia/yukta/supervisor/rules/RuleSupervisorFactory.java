// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Clock;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes rule supervisors from profiles. The settings are the rules file as JSON: {@code {"rules":
 * [...]}}, read as strictly as the file is.
 */
@Component
@NoArgsConstructor
public class RuleSupervisorFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "rule-supervisor";

  /** Writes the settings back to the JSON the rule reader takes. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    // the settings came from JSON, so writing them back cannot fail; reading them can
    return new RuleSupervisor(
        RuleSupervisor.read(JSON.writeValueAsString(profile.settings())), Clock.systemUTC());
  }
}
