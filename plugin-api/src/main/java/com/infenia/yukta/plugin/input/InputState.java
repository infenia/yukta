// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

/**
 * Where an input request is in its life. A terminal state never changes.
 *
 * @since 0.1.0-alpha.4
 */
public enum InputState {

  /** Waiting for an answer from the first tier. */
  OPEN,

  /** Handed up to a later tier. */
  ESCALATED,

  /** Answered; terminal. */
  ANSWERED,

  /** Not answered in time; terminal. */
  EXPIRED,

  /** The run or the node ended, or the asker went away; terminal. */
  CANCELLED;

  /**
   * Whether the state is final.
   *
   * @return true for {@link #ANSWERED}, {@link #EXPIRED} and {@link #CANCELLED}
   */
  public boolean isTerminal() {
    return this == ANSWERED || this == EXPIRED || this == CANCELLED;
  }
}
