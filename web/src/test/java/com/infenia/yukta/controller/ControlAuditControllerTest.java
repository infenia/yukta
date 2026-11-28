// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.ControlAuditEntry;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlAuditController}. */
@NoArgsConstructor
class ControlAuditControllerTest {

  private static ControlAuditController controller() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    final WorkflowProgress run = mock(WorkflowProgress.class);
    when(run.sessionId()).thenReturn("s1");
    when(run.workflowId()).thenReturn("w1");
    when(tracker.getProgressByExecutionId("e1")).thenReturn(run);
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    log.append(ExecutionLogEvent.control(true, "e1", "PauseWorkflowCommand [by key:bob]", null));
    log.append(
        ExecutionLogEvent.control(false, "e1", "StopWorkflowCommand [by rules]", "deny: busy"));
    log.append(ExecutionLogEvent.idempotencyKey("e2", "k"));
    log.append(ExecutionLogEvent.control(true, "e2", "SetLimits(2, 0)", null));
    log.append(ExecutionLogEvent.idempotencyKey("e3", "k2"));
    return new ControlAuditController(log, tracker);
  }

  @Test
  void list_returnsControlCommandsNewestFirstWithTheirActor() {
    final var entries =
        java.util.Objects.requireNonNull(controller().list(100, null, null).block()).data();

    assertThat(entries)
        .extracting(
            ControlAuditEntry::command,
            ControlAuditEntry::actor,
            ControlAuditEntry::applied,
            ControlAuditEntry::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("SetLimits(2, 0)", null, true, null),
            org.assertj.core.groups.Tuple.tuple(
                "StopWorkflowCommand", "rules", false, "deny: busy"),
            org.assertj.core.groups.Tuple.tuple("PauseWorkflowCommand", "key:bob", true, null));
  }

  @Test
  void list_namesTheSessionAndWorkflowOfRunsTheTrackerStillHolds() {
    final var entries =
        java.util.Objects.requireNonNull(controller().list(100, null, null).block()).data();

    assertThat(entries)
        .extracting(
            ControlAuditEntry::executionId,
            ControlAuditEntry::sessionId,
            ControlAuditEntry::workflowId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("e2", null, null),
            org.assertj.core.groups.Tuple.tuple("e1", "s1", "w1"),
            org.assertj.core.groups.Tuple.tuple("e1", "s1", "w1"));
  }

  @Test
  void list_leavesTheRunWithoutAnIdUnresolved() {
    final var log = new InMemoryExecutionLogStore();
    log.append(ExecutionLogEvent.control(true, "", "SetLimits(1, 0)", null));
    final var entries =
        java.util.Objects.requireNonNull(
                new ControlAuditController(log, mock(TaskTrackerService.class))
                    .list(10, null, null)
                    .block())
            .data();

    assertThat(entries).singleElement().satisfies(e -> assertThat(e.sessionId()).isNull());
  }

  @Test
  void list_filtersByExecutionAndLimit() {
    assertThat(java.util.Objects.requireNonNull(controller().list(100, "e1", null).block()).data())
        .hasSize(2);
    assertThat(java.util.Objects.requireNonNull(controller().list(0, null, null).block()).data())
        .hasSize(1);
  }

  @Test
  void entryWithoutStatusHasAnEmptyCommand() {
    final var event =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_APPLIED,
            "e",
            "",
            "",
            null,
            null,
            null,
            null,
            null,
            java.time.Instant.now(),
            null);

    assertThat(ControlAuditEntry.from(event).command()).isEmpty();
  }

  @Test
  void entryWithMultilineReasonIsParsedWhole() {
    final var event =
        ExecutionLogEvent.control(false, "e", "StopWorkflowCommand [by rules]", "deny:\nbusy");

    assertThat(ControlAuditEntry.from(event).reason()).isEqualTo("deny:\nbusy");
  }

  @Test
  void list_withBeforeReturnsOnlyOlderCommands() {
    final var all = controller().list(100, null, java.time.Instant.now().plusSeconds(60)).block();
    final var none = controller().list(100, null, java.time.Instant.EPOCH).block();

    assertThat(java.util.Objects.requireNonNull(all).data()).hasSize(3);
    assertThat(java.util.Objects.requireNonNull(none).data()).isEmpty();
  }
}
