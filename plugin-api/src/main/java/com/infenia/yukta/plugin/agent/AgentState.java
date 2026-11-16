// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

/**
 * Where a conversation is. A conversation only moves along the arrows below; {@link #ENDED} is
 * final.
 *
 * <pre>
 * STARTING -&gt; RUNNING | ENDED
 * RUNNING  -&gt; WAITING | IDLE | ENDED
 * WAITING  -&gt; RUNNING | ENDED
 * IDLE     -&gt; RUNNING | ENDED
 * </pre>
 *
 * @since 0.1.0-alpha.4
 */
public enum AgentState {
  /** The process is starting and the protocol handshake runs. */
  STARTING,
  /** The agent works on a turn. */
  RUNNING,
  /** The agent waits for a person or a supervisor: a request is open or a terminal wants input. */
  WAITING,
  /** Conversation mode only: the turn is over and the agent waits for the next message. */
  IDLE,
  /** Terminal: the conversation has an outcome. */
  ENDED;

  /**
   * Tells whether the conversation may move from this state to another.
   *
   * @param next the state to move to
   * @return true if the arrow exists; false for the same state and for any move out of {@link
   *     #ENDED}
   */
  public boolean canMoveTo(final AgentState next) {
    return switch (this) {
      case STARTING -> next == RUNNING || next == ENDED;
      case RUNNING -> next == WAITING || next == IDLE || next == ENDED;
      case WAITING, IDLE -> next == RUNNING || next == ENDED;
      case ENDED -> false;
    };
  }
}
