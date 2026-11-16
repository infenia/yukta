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
}
