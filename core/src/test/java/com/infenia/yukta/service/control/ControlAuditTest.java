// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlAudit}. */
@NoArgsConstructor
class ControlAuditTest {

  @Test
  void record_writesTheLogEntryWithTheActor_andPassesTheRecordToTheSink() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    final List<AuditRecord> seen = new ArrayList<>();

    ControlAudit.record(
        log,
        seen::add,
        new AuditRecord(false, "e1", "StopWorkflowCommand", "key:bob", "busy", Instant.now()));

    assertThat(log.readAll())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(event.status()).isEqualTo("StopWorkflowCommand [by key:bob]: busy");
            });
    assertThat(seen)
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.applied()).isFalse();
              assertThat(record.executionId()).isEqualTo("e1");
              assertThat(record.command()).isEqualTo("StopWorkflowCommand");
              assertThat(record.actor()).isEqualTo("key:bob");
              assertThat(record.reason()).isEqualTo("busy");
              assertThat(record.timestamp()).isNotNull();
            });
  }

  @Test
  void record_withoutSinkOrActor_onlyWritesTheLog() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

    ControlAudit.record(
        log, null, new AuditRecord(true, "e1", "PauseWorkflowCommand", null, null, Instant.now()));

    assertThat(log.readAll())
        .singleElement()
        .satisfies(e -> assertThat(e.status()).isEqualTo("PauseWorkflowCommand"));
  }

  @Test
  void record_sinkThatFails_neverBreaksTheCommand() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

    ControlAudit.record(
        log,
        r -> {
          throw new IllegalStateException("siem down");
        },
        new AuditRecord(true, "e1", "PauseWorkflowCommand", null, null, Instant.now()));

    assertThat(log.readAll()).hasSize(1);
  }
}
