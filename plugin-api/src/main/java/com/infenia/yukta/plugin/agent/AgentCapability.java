// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * What a conversation can do, as strings: they come from the preset and the mode, and a command the
 * conversation lacks the capability for is refused.
 *
 * @since 0.1.0-alpha.4
 */
@UtilityClass
public class AgentCapability {

  /** Accepts a message while the conversation lives. */
  public static final String MESSAGE = "message";

  /** Delivers a message during a turn; without it a message waits for the end of the turn. */
  public static final String STEER = "steer";

  /** Can stop the current turn without ending the process. */
  public static final String INTERRUPT = "interrupt";

  /** Sends structured requests. */
  public static final String ASK = "ask";

  /** Can continue an earlier session of the harness. */
  public static final String RESUME = "resume";

  /** Has a terminal: input, resize and screen. */
  public static final String TERMINAL = "terminal";

  /** Every known capability. */
  public static final Set<String> ALL = Set.of(MESSAGE, STEER, INTERRUPT, ASK, RESUME, TERMINAL);

  /**
   * Checks a set of capabilities and keeps an immutable copy.
   *
   * @param capabilities the capabilities
   * @return the immutable copy
   * @throws IllegalArgumentException if one is not in {@link #ALL}
   */
  public static Set<String> requireKnown(final Set<String> capabilities) {
    for (final String capability : capabilities) {
      if (!ALL.contains(capability)) {
        throw new IllegalArgumentException(
            "Unknown capability '" + capability + "'; known: " + ALL.stream().sorted().toList());
      }
    }
    return Set.copyOf(capabilities);
  }
}
