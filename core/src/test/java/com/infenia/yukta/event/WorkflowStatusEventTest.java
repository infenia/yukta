// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.testsupport.Timing;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowStatusEvent}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.LinguisticNaming"})
class WorkflowStatusEventTest {

  @Test
  void constructor_explicitTimestamp_preservesAllFields() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event.executionId()).isEqualTo("exec1");
    assertThat(event.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(event.timestamp()).isEqualTo(timestamp);
  }

  @Test
  void create_validInputs_returnsEventWithCurrentTimestamp() {
    final Instant before = Instant.now();
    final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", TaskStatus.RUNNING);
    final Instant after = Instant.now();

    assertThat(event.executionId()).isEqualTo("exec1");
    assertThat(event.status()).isEqualTo(TaskStatus.RUNNING);
    assertThat(event.timestamp()).isNotNull();
    assertThat(event.timestamp())
        .isAfterOrEqualTo(before.minusSeconds(1))
        .isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void create_executionIdPreserved() {
    final String executionId = "workflow-execution-12345";
    final WorkflowStatusEvent event = WorkflowStatusEvent.create(executionId, TaskStatus.PENDING);

    assertThat(event.executionId()).isEqualTo(executionId);
  }

  @Test
  void create_statusPreserved() {
    final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", TaskStatus.FAILURE);

    assertThat(event.status()).isEqualTo(TaskStatus.FAILURE);
  }

  @Test
  void create_multipleStatusTypes() {
    for (final TaskStatus status : TaskStatus.values()) {
      final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", status);
      assertThat(event.status()).isEqualTo(status);
    }
  }

  @Test
  void record_sameFieldValues_areEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec2", TaskStatus.SUCCESS, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentStatus_areNotEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec1", TaskStatus.FAILURE, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    final Instant timestamp1 = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Instant timestamp2 = LocalDateTime.of(2026, 6, 21, 10, 30, 1).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp1);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp2);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event1 =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);
    final WorkflowStatusEvent event2 =
        new WorkflowStatusEvent("exec2", TaskStatus.FAILURE, timestamp);

    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    final String toString = event.toString();
    assertThat(toString).contains("exec1").contains("SUCCESS");
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event.executionId()).isNotNull();
    assertThat(event.status()).isNotNull();
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void create_sequentialEvents_haveIncreasingTimestamps() throws InterruptedException {
    final WorkflowStatusEvent event1 = WorkflowStatusEvent.create("exec1", TaskStatus.RUNNING);
    Timing.nextMillisecond();
    final WorkflowStatusEvent event2 = WorkflowStatusEvent.create("exec1", TaskStatus.SUCCESS);

    assertThat(event2.timestamp()).isAfter(event1.timestamp());
  }

  @Test
  void create_sameExecutionIdMultipleStatuses() {
    final String executionId = "shared-exec";
    final WorkflowStatusEvent startEvent =
        WorkflowStatusEvent.create(executionId, TaskStatus.RUNNING);
    final WorkflowStatusEvent completeEvent =
        WorkflowStatusEvent.create(executionId, TaskStatus.SUCCESS);

    assertThat(startEvent.executionId()).isEqualTo(completeEvent.executionId());
    assertThat(startEvent.status()).isNotEqualTo(completeEvent.status());
  }

  @Test
  void equals_withNull_returnsFalse() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final WorkflowStatusEvent event =
        new WorkflowStatusEvent("exec1", TaskStatus.SUCCESS, timestamp);

    assertThat(event).isNotEqualTo("not a WorkflowStatusEvent");
  }
}
