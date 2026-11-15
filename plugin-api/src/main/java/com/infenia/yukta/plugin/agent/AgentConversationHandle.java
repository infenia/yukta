// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * What a plugin holds while an agent runs: it writes events and state, and reads the commands that
 * clients send. Calls never block and never fail the plugin for a bad value of an event; they are
 * applied in the order they are made.
 *
 * @since 0.1.0-alpha.4
 */
public interface AgentConversationHandle {

  /**
   * Returns the id of the conversation.
   *
   * @return the id
   */
  String id();

  /**
   * Records an event. Core cuts what is too long, removes secrets and gives the event its sequence
   * number. Nothing is recorded once the conversation has ended. Appending a {@link
   * AgentEventKind#RESULT} counts a finished turn.
   *
   * @param kind what the event describes
   * @param itemId the item it belongs to, or null
   * @param data the details, by the keys of the kind
   */
  void emit(AgentEventKind kind, @Nullable String itemId, Map<String, Object> data);

  /**
   * Moves the conversation to a state. A move that is not allowed, a move to the same state and a
   * move after the end are ignored; {@link AgentState#ENDED} is reached only through {@link #end}.
   *
   * @param state the new state
   * @param reason why, or null
   */
  void state(AgentState state, @Nullable String reason);

  /**
   * Tells how many messages wait for the turn to end.
   *
   * @param count the number, not negative
   */
  void queued(int count);

  /**
   * Returns the commands clients send to the agent, from the first one sent after the conversation
   * was opened. There is one subscriber.
   *
   * @return the commands; completes when the conversation ends
   */
  Flux<AgentCommand> commands();

  /**
   * Ends the conversation. Calling it again does nothing.
   *
   * @param outcome one of {@link AgentOutcome#ALL}
   * @param reason why, or null
   * @throws IllegalArgumentException if the outcome is not known
   */
  void end(String outcome, @Nullable String reason);

  /**
   * Returns a handle that drops what it is given and never sends a command: what a plugin uses when
   * there is no gateway.
   *
   * @return the handle
   */
  static AgentConversationHandle none() {
    return NoHandle.INSTANCE;
  }
}
