// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import org.jspecify.annotations.Nullable;

/**
 * An {@link ExecutionSupervisor}'s verdict on a control command.
 *
 * @param allowed true to let the command run
 * @param reason why it was denied; {@code null} when allowed
 */
public record Decision(boolean allowed, @Nullable String reason) {

  /** The command may run. */
  public static final Decision ALLOW = new Decision(true, null);

  /**
   * Refuses the command.
   *
   * @param reason why, shown to the caller
   * @return a denying decision
   */
  public static Decision deny(final String reason) {
    return new Decision(false, reason);
  }
}
