// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static com.infenia.yukta.plugin.input.InputFixtures.NOW;
import static com.infenia.yukta.plugin.input.InputFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.Responder;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionEventInputTest {

  @Test
  void requestEventsTakeTheirIdentityFromTheRequest() {
    final ExecutionEvent requested = new ExecutionEvent.InputRequested(request(), NOW);
    final ExecutionEvent escalated =
        new ExecutionEvent.InputEscalated(request(), "tier timeout", NOW);
    for (final ExecutionEvent event : new ExecutionEvent[] {requested, escalated}) {
      assertThat(event.executionId()).isEqualTo("e1");
      assertThat(event.sessionId()).isEqualTo("s");
      assertThat(event.workflowId()).isEqualTo("w");
      assertThat(event.timestamp()).isEqualTo(NOW);
    }
  }

  @Test
  void theRequestEventsNeedTheirParts() {
    assertThatThrownBy(() -> new ExecutionEvent.InputRequested(null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionEvent.InputRequested(request(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionEvent.InputEscalated(null, "r", NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionEvent.InputEscalated(request(), null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionEvent.InputEscalated(request(), "r", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theOutcomeEventsDoNotCarryTheAnswerText() {
    final ExecutionEvent answered =
        new ExecutionEvent.InputAnswered(
            "e1", "s", "w", "r1", new Responder(Responder.Type.HUMAN, "key:ops"), "approve", NOW);
    assertThat(answered.toString()).doesNotContain("text").doesNotContain("data");
    assertThat(answered.executionId()).isEqualTo("e1");
  }

  @Test
  void everyEventCanBeSwitchedOverExhaustively() {
    final ExecutionEvent[] events = {
      new ExecutionEvent.InputRequested(request(), NOW),
      new ExecutionEvent.InputEscalated(request(), "r", NOW),
      new ExecutionEvent.InputAnswered("e", null, null, "r", Responder.SYSTEM, "d", NOW),
      new ExecutionEvent.InputExpired("e", null, null, "r", NOW),
      new ExecutionEvent.InputCancelled("e", null, null, "r", "run-ended", NOW)
    };
    final StringBuilder seen = new StringBuilder();
    for (final ExecutionEvent event : events) {
      seen.append(
          switch (event) {
            case ExecutionEvent.NodeStatusChanged _ -> "node";
            case ExecutionEvent.WorkflowStatusChanged _ -> "workflow";
            case ExecutionEvent.ControlOutcome _ -> "control";
            case ExecutionEvent.LogLine _ -> "log";
            case ExecutionEvent.EventsMissed _ -> "missed";
            case ExecutionEvent.InputRequested _ -> "requested ";
            case ExecutionEvent.InputEscalated _ -> "escalated ";
            case ExecutionEvent.InputAnswered _ -> "answered ";
            case ExecutionEvent.InputExpired _ -> "expired ";
            case ExecutionEvent.InputCancelled _ -> "cancelled";
            case ExecutionEvent.AgentConversationChanged _ -> "agent";
          });
    }
    assertThat(seen.toString()).isEqualTo("requested escalated answered expired cancelled");
  }

  @Test
  void agentConversationChangeIsEventOfItsRunAndNode() {
    final AgentConversation conversation =
        new AgentConversation(
            "c1",
            "e1",
            "e0",
            "s1",
            "w1",
            "n1",
            "m1",
            "claude-code",
            AgentProtocol.JSONL,
            null,
            Set.of(),
            AgentState.RUNNING,
            null,
            null,
            0,
            0,
            Audience.CURRENT_TIER,
            NOW,
            NOW,
            null,
            0);

    final ExecutionEvent event = new ExecutionEvent.AgentConversationChanged(conversation, NOW);

    assertThat(event.executionId()).isEqualTo("e1");
    assertThat(event.sessionId()).isEqualTo("s1");
    assertThat(event.workflowId()).isEqualTo("w1");
    assertThat(event.nodeId()).isEqualTo("n1");
    assertThatThrownBy(() -> new ExecutionEvent.AgentConversationChanged(null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionEvent.AgentConversationChanged(conversation, null))
        .isInstanceOf(NullPointerException.class);
  }
}
