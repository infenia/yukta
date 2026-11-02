// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a rule can match on when the event is about an input request. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class FieldsInputEventsTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private static InputRequest request() {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key",
            InputKind.APPROVAL,
            "Deploy?",
            Map.of(),
            List.of(),
            null,
            InputRisk.MEDIUM,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "WAIT_FOR_SIGNAL");
    return new InputRequest(
        "r1",
        "key",
        "s",
        "wf",
        "e1",
        "ask",
        spec,
        InputState.OPEN,
        0,
        "auto",
        List.of("supervisor:*"),
        null,
        null,
        NOW,
        NOW);
  }

  @Test
  void requestedEventExposesWhatRulesNeedToAnswerIt() {
    final Map<String, Object> fields =
        Fields.eventFields(new ExecutionEvent.InputRequested(request(), NOW));

    assertThat(fields)
        .containsEntry("executionId", "e1")
        .containsEntry("workflowId", "wf")
        .containsEntry("nodeId", "ask")
        .containsEntry("status", "INPUT_REQUESTED")
        .containsEntry("requestId", "r1")
        .containsEntry("kind", "APPROVAL")
        .containsEntry("risk", "MEDIUM")
        .containsEntry("state", "OPEN")
        .containsEntry("tier", "auto")
        .containsEntry("requester", "WAIT_FOR_SIGNAL");
  }

  @Test
  void escalatedEventAlsoCarriesTheReason() {
    final Map<String, Object> fields =
        Fields.eventFields(new ExecutionEvent.InputEscalated(request(), "tier timeout", NOW));

    assertThat(fields)
        .containsEntry("status", "INPUT_ESCALATED")
        .containsEntry("reason", "tier timeout")
        .containsEntry("risk", "MEDIUM");
  }

  @Test
  void answeredEventNamesTheResponderButNotTheAnswerText() {
    final Map<String, Object> fields =
        Fields.eventFields(
            new ExecutionEvent.InputAnswered(
                "e1",
                "s",
                "wf",
                "r1",
                new Responder(Responder.Type.SUPERVISOR, "risk-watch"),
                "approve",
                NOW));

    assertThat(fields)
        .containsEntry("status", "INPUT_ANSWERED")
        .containsEntry("requestId", "r1")
        .containsEntry("decision", "approve")
        .containsEntry("responder", "risk-watch")
        .containsEntry("responderType", "SUPERVISOR")
        .doesNotContainKey("text");
  }

  @Test
  void expiredAndCancelledEventsAreNamed() {
    final Map<String, Object> expired =
        Fields.eventFields(new ExecutionEvent.InputExpired("e1", "s", "wf", "r1", NOW));
    final Map<String, Object> cancelled =
        Fields.eventFields(
            new ExecutionEvent.InputCancelled("e1", "s", "wf", "r1", "run-ended", NOW));

    assertThat(expired).containsEntry("status", "INPUT_EXPIRED").containsEntry("requestId", "r1");
    assertThat(cancelled)
        .containsEntry("status", "INPUT_CANCELLED")
        .containsEntry("reason", "run-ended")
        .containsEntry("workflowId", "wf");
  }
}
