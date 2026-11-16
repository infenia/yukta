// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A conversation only moves along the arrows of the state table. */
@NoArgsConstructor
class AgentStateTest {

  private static final Map<AgentState, Set<AgentState>> ALLOWED =
      Map.of(
          AgentState.STARTING, EnumSet.of(AgentState.RUNNING, AgentState.ENDED),
          AgentState.RUNNING, EnumSet.of(AgentState.WAITING, AgentState.IDLE, AgentState.ENDED),
          AgentState.WAITING, EnumSet.of(AgentState.RUNNING, AgentState.ENDED),
          AgentState.IDLE, EnumSet.of(AgentState.RUNNING, AgentState.ENDED),
          AgentState.ENDED, EnumSet.noneOf(AgentState.class));

  @Test
  void everyPairFollowsTheTable() {
    for (final AgentState from : AgentState.values()) {
      for (final AgentState to : AgentState.values()) {
        assertThat(from.canMoveTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(ALLOWED.get(from).contains(to));
      }
    }
  }

  @Test
  void stateNeverMovesToItself() {
    for (final AgentState state : AgentState.values()) {
      assertThat(state.canMoveTo(state)).isFalse();
    }
  }
}
