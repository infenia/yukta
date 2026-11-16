// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.conversation;
import static com.infenia.yukta.service.agent.AgentTestSupport.event;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A filter keeps what matches every field that is set and ignores the rest. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AgentFiltersTest {

  private final AgentConversation conversation = conversation("c1");

  @Test
  void conversationFilterWithNothingSetKeepsEverything() {
    assertThat(ConversationFilter.all().matches(conversation)).isTrue();
    assertThat(new ConversationFilter(null, null, null, Set.of(), 5).matches(conversation))
        .isTrue();
  }

  @Test
  void conversationFilterChecksEachFieldItHas() {
    assertThat(
            new ConversationFilter("session-1", "exec-1", "root-1", Set.of(AgentState.RUNNING), 5)
                .matches(conversation))
        .isTrue();
    assertThat(new ConversationFilter("other", null, null, null, 5).matches(conversation))
        .isFalse();
    assertThat(new ConversationFilter(null, "other", null, null, 5).matches(conversation))
        .isFalse();
    assertThat(new ConversationFilter(null, null, "other", null, 5).matches(conversation))
        .isFalse();
    assertThat(
            new ConversationFilter(null, null, null, Set.of(AgentState.IDLE), 5)
                .matches(conversation))
        .isFalse();
  }

  @Test
  void eventFilterWithNothingSetKeepsEverything() {
    assertThat(new EventFilter(null, null, null, null, null).matches(conversation)).isTrue();
    assertThat(
            new EventFilter(null, null, null, null, Set.of())
                .matches(event("c1", AgentEventKind.TOOL)))
        .isTrue();
  }

  @Test
  void eventFilterChecksEachFieldItHas() {
    assertThat(new EventFilter("c1", "session-1", "exec-1", "root-1", null).matches(conversation))
        .isTrue();
    assertThat(EventFilter.forConversation("c1").matches(conversation)).isTrue();
    assertThat(EventFilter.forConversation("other").matches(conversation)).isFalse();
    assertThat(new EventFilter(null, "other", null, null, null).matches(conversation)).isFalse();
    assertThat(new EventFilter(null, null, "other", null, null).matches(conversation)).isFalse();
    assertThat(new EventFilter(null, null, null, "other", null).matches(conversation)).isFalse();
  }

  @Test
  void eventFilterKeepsOnlyTheKindsItNames() {
    final EventFilter tools = new EventFilter(null, null, null, null, Set.of(AgentEventKind.TOOL));

    assertThat(tools.matches(event("c1", AgentEventKind.TOOL))).isTrue();
    assertThat(tools.matches(event("c1", AgentEventKind.MESSAGE))).isFalse();
  }

  @Test
  void anEmptyIdentifierDoesNotFilter() {
    assertThat(new ConversationFilter("", "", "", null, 5).matches(conversation)).isTrue();
    assertThat(new EventFilter("", "", "", "", null).matches(conversation)).isTrue();
    assertThat(EventFilter.forConversation("").matches(conversation)).isTrue();
  }
}
