/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WorkflowStatusEventTest {

  @Test
  void constructor_explicitTimestamp_preservesAllFields() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertEquals("exec1", event.executionId());
    assertEquals("COMPLETED", event.status());
    assertEquals(timestamp, event.timestamp());
  }

  @Test
  void create_validInputs_returnsEventWithCurrentTimestamp() {
    LocalDateTime before = LocalDateTime.now();
    WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", "STARTED");
    LocalDateTime after = LocalDateTime.now();

    assertEquals("exec1", event.executionId());
    assertEquals("STARTED", event.status());
    assertNotNull(event.timestamp());
    assertTrue(
        !event.timestamp().isBefore(before.minusSeconds(1))
            && !event.timestamp().isAfter(after.plusSeconds(1)));
  }

  @Test
  void create_executionIdPreserved() {
    String executionId = "workflow-execution-12345";
    WorkflowStatusEvent event = WorkflowStatusEvent.create(executionId, "PENDING");

    assertEquals(executionId, event.executionId());
  }

  @Test
  void create_statusPreserved() {
    WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", "FAILED");

    assertEquals("FAILED", event.status());
  }

  @Test
  void create_multipleStatusTypes() {
    String[] statuses = {"INITIATED", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"};

    for (String status : statuses) {
      WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", status);
      assertEquals(status, event.status());
    }
  }

  @Test
  void record_sameFieldValues_areEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertEquals(event1, event2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec2", "COMPLETED", timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void record_differentStatus_areNotEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "FAILED", timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    LocalDateTime timestamp1 = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    LocalDateTime timestamp2 = LocalDateTime.of(2026, 6, 21, 10, 30, 1);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp1);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp2);

    assertNotEquals(event1, event2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event1 = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);
    WorkflowStatusEvent event2 = new WorkflowStatusEvent("exec2", "FAILED", timestamp);

    assertNotEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    String toString = event.toString();
    assertTrue(toString.contains("exec1"));
    assertTrue(toString.contains("COMPLETED"));
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertTrue(event.executionId() instanceof String);
    assertTrue(event.status() instanceof String);
    assertTrue(event.timestamp() instanceof LocalDateTime);
  }

  @Test
  void create_sequentialEvents_haveIncreasingTimestamps() throws InterruptedException {
    WorkflowStatusEvent event1 = WorkflowStatusEvent.create("exec1", "STARTED");
    Thread.sleep(10);
    WorkflowStatusEvent event2 = WorkflowStatusEvent.create("exec1", "COMPLETED");

    assertTrue(event2.timestamp().isAfter(event1.timestamp()));
  }

  @Test
  void create_sameExecutionIdMultipleStatuses() {
    String executionId = "shared-exec";
    WorkflowStatusEvent startEvent = WorkflowStatusEvent.create(executionId, "STARTED");
    WorkflowStatusEvent completeEvent = WorkflowStatusEvent.create(executionId, "COMPLETED");

    assertEquals(startEvent.executionId(), completeEvent.executionId());
    assertNotEquals(startEvent.status(), completeEvent.status());
  }

  @Test
  void equals_withNull_returnsFalse() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertNotEquals(event, null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowStatusEvent event = new WorkflowStatusEvent("exec1", "COMPLETED", timestamp);

    assertNotEquals(event, "not a WorkflowStatusEvent");
  }
}
