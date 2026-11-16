// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.supervisor.rules.RuleSet.Then;
import com.infenia.yukta.supervisor.rules.RuleSet.When;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Rules that steer an agent. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RuleAgentActionsTest {

  private static final Instant START = Instant.parse("2026-11-16T10:00:00Z");

  private static ExecutionEvent.AgentConversationChanged changed(
      final AgentState state, final long secondsRunning) {
    final AgentConversation conversation =
        new AgentConversation(
            "c-1",
            "e-1",
            "e-1",
            "s-1",
            "w-1",
            "n-1",
            "m-1",
            "claude-code",
            AgentProtocol.JSONL,
            null,
            Set.of("interrupt"),
            state,
            null,
            null,
            1,
            0,
            new Audience(List.of("supervisor:*")),
            START,
            START,
            null,
            1);
    return new ExecutionEvent.AgentConversationChanged(
        conversation, START.plusSeconds(secondsRunning));
  }

  private static When when(final String condition) {
    return new When(
        "AgentConversationChanged", null, null, null, null, condition, null, null, null);
  }

  private static Then then(final String command) {
    return new Then(command, null, "too slow", null, null, null, null, "wrap up", true, "failure");
  }

  @Test
  void interruptsAnAgentThatHasBeenRunningLongerThanTheLimit() {
    final EventRule rule =
        new EventRule(
            "slow", when("state == 'RUNNING' and runningSeconds > 600"), then("AgentInterrupt"));

    assertThat(rule.matches(changed(AgentState.RUNNING, 601), 0)).isTrue();
    assertThat(rule.matches(changed(AgentState.RUNNING, 60), 0)).isFalse();
    assertThat(rule.matches(changed(AgentState.IDLE, 601), 0)).isFalse();
    assertThat(rule.command(changed(AgentState.RUNNING, 601), "new"))
        .isEqualTo(new ExecutionControlCommand.AgentInterruptCommand("e-1", "n-1", "c-1"));
  }

  @Test
  void sendsMessageAndEndsConversation() {
    final ExecutionEvent event = changed(AgentState.RUNNING, 700);

    assertThat(new EventRule("m", when(null), then("AgentMessage")).command(event, "new"))
        .isEqualTo(
            new ExecutionControlCommand.AgentMessageCommand("e-1", "n-1", "c-1", "wrap up", true));
    assertThat(new EventRule("e", when(null), then("AgentEnd")).command(event, "new"))
        .isEqualTo(
            new ExecutionControlCommand.AgentEndCommand(
                "e-1", "n-1", "c-1", "failure", "too slow"));
  }

  @Test
  void ruleThatSteersAgentReactsToNoOtherEvent() {
    assertThatThrownBy(() ->
            new EventRule(
                "bad",
                new When("NodeStatusChanged", null, null, null, null, null, null, null, null),
                then("AgentInterrupt")))
        .hasMessageContaining("must react to AgentConversationChanged");
    final EventRule open =
        new EventRule(
            "any",
            new When(null, null, null, null, null, null, null, null, null),
            then("AgentInterrupt"));
    assertThat(
            open.matches(
                new ExecutionEvent.NodeStatusChanged(
                    "e-1", "s-1", "w-1", "n-1", "m", "FAILED", java.util.Map.of(), START),
                0))
        .isFalse();
  }

  @Test
  void agentMessageRuleNeedsItsText() {
    assertThatThrownBy(() ->
            new EventRule(
                "t", when(null), new Then("AgentMessage", null, null, null, null, null, null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("then.text");
  }
}
