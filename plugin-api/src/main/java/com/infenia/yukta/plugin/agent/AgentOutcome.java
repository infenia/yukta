// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * How a conversation ended. The values are the strings the REST, MCP and CLI clients see.
 *
 * @since 0.1.0-alpha.4
 */
@UtilityClass
public class AgentOutcome {

  /** The agent finished what it was asked. */
  public static final String SUCCESS = "success";

  /** The agent or the node failed. */
  public static final String FAILURE = "failure";

  /** The conversation stopped before the agent finished: ended by a person, timed out, cut. */
  public static final String INCOMPLETE = "incomplete";

  /** Every accepted outcome. */
  public static final Set<String> ALL = Set.of(SUCCESS, FAILURE, INCOMPLETE);

  /**
   * Checks an outcome.
   *
   * @param outcome the outcome
   * @return the same outcome
   * @throws IllegalArgumentException if it is not one of {@link #ALL}
   */
  public static String require(final String outcome) {
    if (outcome == null || !ALL.contains(outcome)) {
      throw new IllegalArgumentException(
          "outcome must be one of " + ALL.stream().sorted().toList() + ", not '" + outcome + "'");
    }
    return outcome;
  }
}
