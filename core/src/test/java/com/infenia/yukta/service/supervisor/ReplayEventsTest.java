// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ReplayEventsTest {

  @Test
  void runBecomesItsStatusEvents() {
    final List<ExecutionEvent> events =
        ReplayEvents.from(
            List.of(
                ExecutionLogEvent.started("e", "s", "w", "v"),
                ExecutionLogEvent.finished("e", "s", "w", "FAILURE", Map.of("n", "FAILURE"))));

    assertThat(events).hasSize(3);
    assertThat(events.get(0))
        .isInstanceOfSatisfying(
            ExecutionEvent.WorkflowStatusChanged.class,
            changed -> assertThat(changed.status()).isEqualTo("RUNNING"));
    assertThat(events.get(1))
        .isInstanceOfSatisfying(
            ExecutionEvent.NodeStatusChanged.class,
            changed -> {
              assertThat(changed.nodeId()).isEqualTo("n");
              assertThat(changed.status()).isEqualTo("FAILURE");
            });
    assertThat(events.get(2))
        .isInstanceOfSatisfying(
            ExecutionEvent.WorkflowStatusChanged.class,
            changed -> assertThat(changed.status()).isEqualTo("FAILURE"));
  }

  @Test
  void controlEntriesAreNotEvents() {
    assertThat(ReplayEvents.from(List.of(ExecutionLogEvent.control(true, "e", "Pause", "reason"))))
        .isEmpty();
  }

  @Test
  void finishedNodeAndAbortedRunBecomeEvents() {
    final ExecutionLogEvent started = ExecutionLogEvent.started("e", "s", "w", "v");
    final List<ExecutionEvent> events =
        ReplayEvents.from(
            List.of(
                ExecutionLogEvent.nodeFinished("e", "s", "w", "a"),
                withoutStatus(ExecutionLogEvent.nodeFinished("e", "s", "w", "b")),
                ExecutionLogEvent.aborted(started, Map.of("c", "SUCCESS")),
                withoutStatus(ExecutionLogEvent.finished("e", "s", "w", "SUCCESS", Map.of()))));

    assertThat(events)
        .extracting(
            event ->
                event instanceof ExecutionEvent.NodeStatusChanged node
                    ? node.nodeId() + "=" + node.status()
                    : "run=" + ((ExecutionEvent.WorkflowStatusChanged) event).status())
        .containsExactly("a=SUCCESS", "b=SUCCESS", "c=SUCCESS", "run=ABORTED", "run=FAILURE");
  }

  private static ExecutionLogEvent withoutStatus(final ExecutionLogEvent event) {
    return new ExecutionLogEvent(
        event.type(),
        event.executionId(),
        event.sessionId(),
        event.workflowId(),
        null,
        null,
        event.nodeStatuses(),
        event.nodeId(),
        null,
        Instant.now(),
        null);
  }
}
