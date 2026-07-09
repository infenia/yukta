// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowStatusEvent}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.LinguisticNaming"})
class WorkflowStatusEventTest {

  @Test
  void constructor_explicitTimestamp_preservesAllFields() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event.executionId()).isEqualTo("exec1");
    assertThat(event.status()).isEqualTo("COMPLETED");
    assertThat(event.timestamp()).isEqualTo(timestamp);
  }

  @Test
  void create_validInputs_returnsEventWithCurrentTimestamp() {
    final LocalDateTime before = LocalDateTime.now(ZoneId.systemDefault());
    final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", "STARTED");
    final LocalDateTime after = LocalDateTime.now(ZoneId.systemDefault());

    assertThat(event.executionId()).isEqualTo("exec1");
    assertThat(event.status()).isEqualTo("STARTED");
    assertThat(event.timestamp()).isNotNull();
    assertThat(event.timestamp())
        .isAfterOrEqualTo(before.minusSeconds(1))
        .isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void create_executionIdPreserved() {
    final String executionId = "workflow-execution-12345";
    final WorkflowStatusEvent event = WorkflowStatusEvent.create(executionId, "PENDING");

    assertThat(event.executionId()).isEqualTo(executionId);
  }

  @Test
  void create_statusPreserved() {
    final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", "FAILED");

    assertThat(event.status()).isEqualTo("FAILED");
  }

  @Test
  void create_multipleStatusTypes() {
    final String[] statuses = {
      "INITIATED", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"
    };

    for (final String status : statuses) {
      final WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", status);
      assertThat(event.status()).isEqualTo(status);
    }
  }

  @Test
  void record_sameFieldValues_areEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec2", "COMPLETED", timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentStatus_areNotEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "FAILED", timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    final LocalDateTime timestamp1 = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final LocalDateTime timestamp2 = LocalDateTime.of(2026, 6, 21, 10, 30, 1);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp1);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp2);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    final WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec2", "FAILED", timestamp);

    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    final String toString = event.toString();
    assertThat(toString).contains("exec1").contains("COMPLETED");
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event.executionId()).isNotNull();
    assertThat(event.status()).isNotNull();
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void create_sequentialEvents_haveIncreasingTimestamps() throws InterruptedException {
    final WorkflowStatusEvent event1 = WorkflowStatusEvent.create("exec1", "STARTED");
    Thread.sleep(10);
    final WorkflowStatusEvent event2 = WorkflowStatusEvent.create("exec1", "COMPLETED");

    assertThat(event2.timestamp()).isAfter(event1.timestamp());
  }

  @Test
  void create_sameExecutionIdMultipleStatuses() {
    final String executionId = "shared-exec";
    final WorkflowStatusEvent startEvent = WorkflowStatusEvent.create(executionId, "STARTED");
    final WorkflowStatusEvent completeEvent = WorkflowStatusEvent.create(executionId, "COMPLETED");

    assertThat(startEvent.executionId()).isEqualTo(completeEvent.executionId());
    assertThat(startEvent.status()).isNotEqualTo(completeEvent.status());
  }

  @Test
  void equals_withNull_returnsFalse() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertThat(event).isNotEqualTo("not a WorkflowStatusEvent");
  }
}
