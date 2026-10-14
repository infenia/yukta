// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.response.ControlAuditEntry;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlAuditController}. */
@NoArgsConstructor
class ControlAuditControllerTest {

  private static ControlAuditController controller() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    log.append(ExecutionLogEvent.control(true, "e1", "PauseWorkflowCommand [by key:bob]", null));
    log.append(
        ExecutionLogEvent.control(false, "e1", "StopWorkflowCommand [by rules]", "deny: busy"));
    log.append(ExecutionLogEvent.control(true, "e2", "SetLimits(2, 0)", null));
    return new ControlAuditController(log);
  }

  @Test
  void list_returnsControlCommandsNewestFirstWithTheirActor() {
    final var entries =
        java.util.Objects.requireNonNull(controller().list(100, null).block()).data();

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
  void list_filtersByExecutionAndLimit() {
    assertThat(java.util.Objects.requireNonNull(controller().list(100, "e1").block()).data())
        .hasSize(2);
    assertThat(java.util.Objects.requireNonNull(controller().list(0, null).block()).data())
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
            java.time.Instant.now());

    assertThat(ControlAuditEntry.from(event).command()).isEmpty();
  }

  @Test
  void entryWithMultilineReasonIsParsedWhole() {
    final var event =
        ExecutionLogEvent.control(false, "e", "StopWorkflowCommand [by rules]", "deny:\nbusy");

    assertThat(ControlAuditEntry.from(event).reason()).isEqualTo("deny:\nbusy");
  }
}
