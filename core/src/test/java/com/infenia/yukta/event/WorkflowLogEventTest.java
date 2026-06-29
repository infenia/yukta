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

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowLogEvent}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.LinguisticNaming"})
class WorkflowLogEventTest {

  @Test
  void create_validInputs_createsEventWithProvidedDataAndRecentTimestamp() {
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "log line 1");

    assertThat(event.executionId()).isEqualTo("exec1");
    assertThat(event.line()).isEqualTo("log line 1");
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void create_validInputs_executionIdPreserved() {
    final String executionId = "workflow-12345";
    final WorkflowLogEvent event = WorkflowLogEvent.create(executionId, "some log");

    assertThat(event.executionId()).isEqualTo(executionId);
  }

  @Test
  void create_validInputs_linePreserved() {
    final String logLine = "Task completed successfully";
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", logLine);

    assertThat(event.line()).isEqualTo(logLine);
  }

  @Test
  void create_multilineLogContent_preserved() {
    final String multiLineLog = "Error occurred\nStack trace follows\nException details";
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", multiLineLog);

    assertThat(event.line()).isEqualTo(multiLineLog);
  }

  @Test
  void create_emptyLineString_accepted() {
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "");

    assertThat(event.line()).isEqualTo("");
  }

  @Test
  void constructor_allFieldsProvided_createsRecord() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event = new WorkflowLogEvent("exec2", "line 2", timestamp);

    assertThat(event.executionId()).isEqualTo("exec2");
    assertThat(event.line()).isEqualTo("line 2");
    assertThat(event.timestamp()).isEqualTo(timestamp);
  }

  @Test
  void equals_identicalFieldValues_returnsTrue() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp);

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void equals_differentExecutionId_returnsFalse() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec2", "log line", timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void equals_differentLine_returnsFalse() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "line 1", timestamp);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "line 2", timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void equals_differentTimestamp_returnsFalse() {
    final LocalDateTime timestamp1 = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final LocalDateTime timestamp2 = LocalDateTime.of(2026, 6, 21, 10, 30, 1);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp1);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp2);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void hashCode_identicalFieldValues_returnsSameHash() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec1", "log line", timestamp);

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void hashCode_differentValues_likelyDifferentHash() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event1 = new WorkflowLogEvent("exec1", "log line", timestamp);
    final WorkflowLogEvent event2 = new WorkflowLogEvent("exec2", "log line", timestamp);

    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event = new WorkflowLogEvent("exec1", "test log", timestamp);

    final String toString = event.toString();
    assertThat(toString).contains("exec1");
    assertThat(toString).contains("test log");
  }

  @Test
  void create_timestampIsApproximatelyNow() {
    final LocalDateTime before = LocalDateTime.now(ZoneId.systemDefault());
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "log message");
    final LocalDateTime after = LocalDateTime.now(ZoneId.systemDefault());

    assertThat(event.timestamp())
        .isAfterOrEqualTo(before.minusSeconds(1))
        .isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final WorkflowLogEvent event = new WorkflowLogEvent("exec1", "line", timestamp);

    assertThat(event.executionId()).isInstanceOf(String.class);
    assertThat(event.line()).isInstanceOf(String.class);
    assertThat(event.timestamp()).isInstanceOf(LocalDateTime.class);
  }

  @Test
  void create_specialCharactersInLogLine_preserved() {
    final String specialLog = "Status: [COMPLETED] | Duration: 500ms | Errors: 0";
    final WorkflowLogEvent event = WorkflowLogEvent.create("exec1", specialLog);

    assertThat(event.line()).isEqualTo(specialLog);
  }
}
