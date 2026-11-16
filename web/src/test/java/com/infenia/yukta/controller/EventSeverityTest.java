// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.input.Audience;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link EventSeverity}. */
@NoArgsConstructor
class EventSeverityTest {

  /** Timestamp of every event built here. */
  private static final Instant NOW = Instant.now();

  @ParameterizedTest
  @CsvSource({
    "RUNNING,INFO",
    "SUCCESS,INFO",
    "UPSTREAM_FAILED,INFO",
    "CANCELLED,WARN",
    "STOPPED,WARN",
    "ERROR,ERROR",
    "FAILURE,ERROR",
    "TIMED_OUT,ERROR"
  })
  void nodeStatus(final String status, final EventSeverity expected) {
    final var event =
        new ExecutionEvent.NodeStatusChanged("e", "s", "w", "n", "m", status, Map.of(), NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"RUNNING,INFO", "COMPLETED,INFO", "CANCELLED,WARN", "FAILURE,ERROR", "ABORTED,ERROR"})
  void workflowStatus(final String status, final EventSeverity expected) {
    final var event = new ExecutionEvent.WorkflowStatusChanged("e", "s", "w", status, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"true,INFO", "false,WARN"})
  void controlOutcome(final boolean applied, final EventSeverity expected) {
    final var event =
        new ExecutionEvent.ControlOutcome("e", null, null, "Pause", null, applied, null, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"DEBUG,INFO", "INFO,INFO", "WARN,WARN", "ERROR,ERROR"})
  void logLine_takesTheLevelItCarries(final LogLevel level, final EventSeverity expected) {
    final var event =
        new ExecutionEvent.LogLine("e", "s", "wf", "n", "any text at all", level, NOW);

    assertThat(EventSeverity.of(event)).isEqualTo(expected);
  }

  @Test
  void eventsMissed_isWarning() {
    assertThat(EventSeverity.of(new ExecutionEvent.EventsMissed(3, NOW)))
        .isEqualTo(EventSeverity.WARN);
  }

  @ParameterizedTest
  @CsvSource({"warn,WARN", " ERROR ,ERROR", "Info,INFO"})
  void parse_acceptsAnyCase(final String name, final EventSeverity expected) {
    assertThat(EventSeverity.parse(name)).isEqualTo(expected);
  }

  @Test
  void inputEvents_areRatedByHowMuchTheyNeedPeople() {
    final var spec =
        new com.infenia.yukta.plugin.input.InputRequestSpec(
            "k",
            com.infenia.yukta.plugin.input.InputKind.TEXT,
            "p",
            Map.of(),
            java.util.List.of(),
            null,
            com.infenia.yukta.plugin.input.InputRisk.LOW,
            Audience.CURRENT_TIER,
            java.time.Duration.ofMinutes(1),
            com.infenia.yukta.plugin.input.TimeoutAction.FAIL,
            null,
            null,
            "X");
    final var request =
        new com.infenia.yukta.plugin.input.InputRequest(
            "r",
            "k",
            "s",
            "w",
            "e",
            "n",
            spec,
            com.infenia.yukta.plugin.input.InputState.OPEN,
            0,
            "t",
            java.util.List.of(),
            null,
            null,
            NOW,
            NOW);

    assertThat(EventSeverity.of(new ExecutionEvent.InputRequested(request, NOW)))
        .isEqualTo(EventSeverity.INFO);
    assertThat(
            EventSeverity.of(
                new ExecutionEvent.InputAnswered(
                    "e",
                    "s",
                    "w",
                    "r",
                    com.infenia.yukta.plugin.input.Responder.SYSTEM,
                    "approve",
                    NOW)))
        .isEqualTo(EventSeverity.INFO);
    assertThat(
            EventSeverity.of(
                new ExecutionEvent.InputCancelled("e", "s", "w", "r", "run-ended", NOW)))
        .isEqualTo(EventSeverity.INFO);
    assertThat(EventSeverity.of(new ExecutionEvent.InputEscalated(request, "tier timeout", NOW)))
        .isEqualTo(EventSeverity.WARN);
    assertThat(EventSeverity.of(new ExecutionEvent.InputExpired("e", "s", "w", "r", NOW)))
        .isEqualTo(EventSeverity.WARN);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"failure,WARN", "success,INFO", "NULL,INFO"},
      nullValues = "NULL")
  void agentConversationIsWarnedOnlyWhenItFailed(
      final String outcome, final EventSeverity expected) {
    final AgentConversation conversation =
        new AgentConversation(
            "c1",
            "e1",
            "e1",
            "s1",
            "w1",
            "n1",
            "m1",
            "claude-code",
            AgentProtocol.JSONL,
            null,
            Set.of(),
            AgentState.RUNNING,
            outcome,
            null,
            0,
            0,
            Audience.CURRENT_TIER,
            NOW,
            NOW,
            null,
            0);

    assertThat(EventSeverity.of(new ExecutionEvent.AgentConversationChanged(conversation, NOW)))
        .isEqualTo(expected);
  }
}
