// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEvent;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which events to read. The fields about the run select conversations, so one filter can read the
 * merged events of every agent of a run. A field that is null or empty does not filter.
 *
 * @param conversationId one conversation
 * @param sessionId the session
 * @param executionId one run
 * @param rootExecutionId a run and every run below it
 * @param kinds the kinds to keep
 */
public record EventFilter(
    @Nullable String conversationId,
    @Nullable String sessionId,
    @Nullable String executionId,
    @Nullable String rootExecutionId,
    @Nullable Set<AgentEventKind> kinds) {

  /**
   * Keeps an immutable copy of the kinds.
   *
   * @param conversationId one conversation
   * @param sessionId the session
   * @param executionId one run
   * @param rootExecutionId the root of a tree of runs
   * @param kinds the kinds to keep
   */
  public EventFilter {
    kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
  }

  /**
   * Returns a filter for the events of one conversation.
   *
   * @param conversationId the conversation
   * @return the filter
   */
  public static EventFilter forConversation(final String conversationId) {
    return new EventFilter(conversationId, null, null, null, null);
  }

  /**
   * Tells whether the events of a conversation can pass the filter.
   *
   * @param conversation the conversation
   * @return true if it matches every conversation field that is set
   */
  public boolean matches(final AgentConversation conversation) {
    return (conversationId == null || conversationId.equals(conversation.id()))
        && (sessionId == null || sessionId.equals(conversation.sessionId()))
        && (executionId == null || executionId.equals(conversation.executionId()))
        && (rootExecutionId == null || rootExecutionId.equals(conversation.rootExecutionId()));
  }

  /**
   * Tells whether an event passes the kind filter.
   *
   * @param event the event
   * @return true if no kinds are set or the event's kind is one of them
   */
  public boolean matches(final AgentEvent event) {
    return kinds == null || kinds.isEmpty() || kinds.contains(event.kind());
  }
}
