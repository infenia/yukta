// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;

/**
 * One change on the live stream: a conversation that was created or changed, or an event that was
 * appended. {@link #seq()} is the change sequence of the store.
 */
public sealed interface AgentChange {

  /**
   * Returns the change sequence number.
   *
   * @return the number; strictly increasing over the store
   */
  long seq();

  /**
   * A conversation was opened or its record changed.
   *
   * @param conversation the record after the change
   */
  record ConversationChanged(AgentConversation conversation) implements AgentChange {
    @Override
    public long seq() {
      return conversation.seq();
    }
  }

  /**
   * An event was appended.
   *
   * @param event the event, with its sequence number
   */
  record EventAppended(AgentEvent event) implements AgentChange {
    @Override
    public long seq() {
      return event.seq();
    }
  }
}
