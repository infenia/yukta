// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The in-memory store: the shared contract, and what only it promises. */
@NoArgsConstructor
class InMemoryAgentConversationStoreTest extends AgentConversationStoreContract {

  @Override
  protected AgentConversationStore create(
      final int conversations, final int events, final long ringBytes) {
    return new InMemoryAgentConversationStore(conversations, events, ringBytes);
  }

  @Test
  void theEpochDiffersBetweenStores() {
    assertThat(create(10, 10, 10).epoch()).isNotEqualTo(create(10, 10, 10).epoch());
  }

  @Test
  void everyBoundMustBeAtLeastOne() {
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryAgentConversationStore(1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static com.infenia.yukta.plugin.agent.AgentEvent terminal(final String data) {
    return new com.infenia.yukta.plugin.agent.AgentEvent(
        0,
        "a",
        AgentTestSupport.NOW,
        com.infenia.yukta.plugin.agent.AgentEventKind.TERMINAL,
        null,
        java.util.Map.of("data", data));
  }

  @Test
  void unpaddedOutputCountsItsDecodedBytes() {
    final AgentConversationStore store = create(10, 10, 2);
    store.save(AgentTestSupport.conversation("a")).block();
    // "AQ" is one byte and "AQI" two, both without padding; "AQID" is three
    store.append(terminal("AQ")).block();
    store.append(terminal("AQI")).block();
    store.append(terminal("AQID")).block();

    assertThat(store.events(EventFilter.forConversation("a"), 0, 10).collectList().block())
        .hasSize(1);
  }

  @Test
  void outputWithNoBytesIsStillBounded() {
    final AgentConversationStore store = create(10, 10, 1_000_000);
    store.save(AgentTestSupport.conversation("a")).block();
    for (int i = 0; i < 10_005; i++) {
      store.append(terminal("")).block();
    }

    assertThat(store.events(EventFilter.forConversation("a"), 0, 20_000).collectList().block())
        .hasSizeLessThanOrEqualTo(10_000);
  }
}
