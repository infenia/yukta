// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentState;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which conversations to list. A field that is null or empty does not filter.
 *
 * @param sessionId the session
 * @param executionId one run
 * @param rootExecutionId a run and every run below it
 * @param states the states to keep
 * @param limit the most conversations to return, at least 1
 */
public record ConversationFilter(
    @Nullable String sessionId,
    @Nullable String executionId,
    @Nullable String rootExecutionId,
    @Nullable Set<AgentState> states,
    int limit) {

  /** The limit of {@link #all()}. */
  public static final int DEFAULT_LIMIT = 200;

  /**
   * Keeps an immutable copy of the states and checks the limit.
   *
   * @param sessionId the session
   * @param executionId one run
   * @param rootExecutionId the root of a tree of runs
   * @param states the states to keep
   * @param limit the most conversations to return
   */
  public ConversationFilter {
    states = states == null ? Set.of() : Set.copyOf(states);
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
  }

  /**
   * Returns a filter that keeps every conversation, up to {@link #DEFAULT_LIMIT}.
   *
   * @return the filter
   */
  public static ConversationFilter all() {
    return new ConversationFilter(null, null, null, null, DEFAULT_LIMIT);
  }

  /**
   * Tells whether a conversation passes the filter, ignoring the limit.
   *
   * @param conversation the conversation
   * @return true if it matches every field that is set
   */
  public boolean matches(final AgentConversation conversation) {
    return (sessionId == null || sessionId.equals(conversation.sessionId()))
        && (executionId == null || executionId.equals(conversation.executionId()))
        && (rootExecutionId == null || rootExecutionId.equals(conversation.rootExecutionId()))
        && (states == null || states.isEmpty() || states.contains(conversation.state()));
  }
}
