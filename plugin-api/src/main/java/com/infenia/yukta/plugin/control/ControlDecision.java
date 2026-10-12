// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.List;

/**
 * What a {@link ControlPlanePlugin} decided about one event.
 *
 * @param commands the commands to apply; empty for no opinion
 * @param confidence how sure the plugin is, from 0 to 1; rules are always 1
 * @param source who decided
 */
public record ControlDecision(
    List<ExecutionControlCommand> commands, double confidence, Source source) {

  /** Who made a decision. */
  public enum Source {
    /** Declarative rules. */
    RULE,
    /** A model. */
    MODEL
  }

  /** Keeps an immutable copy of the commands. */
  public ControlDecision {
    commands = List.copyOf(commands);
  }

  /**
   * No opinion.
   *
   * @param source who abstained
   * @return a decision without commands
   */
  public static ControlDecision none(final Source source) {
    return new ControlDecision(List.of(), 0, source);
  }
}
