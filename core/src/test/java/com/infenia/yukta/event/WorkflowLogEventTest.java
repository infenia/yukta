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

class WorkflowLogEventTest {

  @Test
  void create_validInputs_createsEventWithProvidedDataAndRecentTimestamp() {
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "log line 1");

    assertEquals("exec1", event.executionId());
    assertEquals("log line 1", event.line());
    assertNotNull(event.timestamp());
  }

  @Test
  void create_validInputs_executionIdPreserved() {
    String executionId = "workflow-12345";
    WorkflowLogEvent event = WorkflowLogEvent.create(executionId, "some log");

    assertEquals(executionId, event.executionId());
  }

  @Test
  void create_validInputs_linePreserved() {
    String logLine = "Task completed successfully";
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", logLine);

    assertEquals(logLine, event.line());
  }

  @Test
  void create_multilineLogContent_preserved() {
    String multiLineLog = "Error occurred\nStack trace follows\nException details";
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", multiLineLog);

    assertEquals(multiLineLog, event.line());
  }

  @Test
  void create_emptyLineString_accepted() {
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "");

    assertEquals("", event.line());
  }

  @Test
  void constructor_allFieldsProvided_createsRecord() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event = new WorkflowLogEvent("exec2", "line 2", timestamp);

    assertEquals("exec2", event.executionId());
    assertEquals("line 2", event.line());
    assertEquals(timestamp, event.timestamp());
  }

  @Test
  void equals_identicalFieldValues_returnsTrue() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp);

    assertEquals(event1, event2);
  }

  @Test
  void equals_differentExecutionId_returnsFalse() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec2", "log line", timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void equals_differentLine_returnsFalse() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "line 1", timestamp);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "line 2", timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void equals_differentTimestamp_returnsFalse() {
    LocalDateTime timestamp1 = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    LocalDateTime timestamp2 = LocalDateTime.of(2026, 6, 21, 10, 30, 1);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp1);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp2);

    assertNotEquals(event1, event2);
  }

  @Test
  void hashCode_identicalFieldValues_returnsSameHash() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp);

    assertEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void hashCode_differentValues_likelyDifferentHash() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    WorkflowLogEvent event2 = new WorkflowLogEvent("exec2", "log line", timestamp);

    assertNotEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event = new WorkflowLogEvent("exec1", "test log", timestamp);

    String toString = event.toString();
    assertTrue(toString.contains("exec1"));
    assertTrue(toString.contains("test log"));
  }

  @Test
  void create_timestampIsApproximatelyNow() {
    LocalDateTime before = LocalDateTime.now();
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "log message");
    LocalDateTime after = LocalDateTime.now();

    assertTrue(
        !event.timestamp().isBefore(before.minusSeconds(1))
            && !event.timestamp().isAfter(after.plusSeconds(1)));
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    WorkflowLogEvent event = new WorkflowLogEvent("exec1", "line", timestamp);

    assertTrue(event.executionId() instanceof String);
    assertTrue(event.line() instanceof String);
    assertTrue(event.timestamp() instanceof LocalDateTime);
  }

  @Test
  void create_specialCharactersInLogLine_preserved() {
    String specialLog = "Status: [COMPLETED] | Duration: 500ms | Errors: 0";
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", specialLog);

    assertEquals(specialLog, event.line());
  }
}
