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
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ExecutionRecordTest. */
@NoArgsConstructor
class ExecutionRecordTest {

  /** Session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Execution ID test constant. */
  private static final String EXEC_ID_456 = "exec-456";

  /** Completed status constant. */
  private static final String STATUS_COMPLETED = "COMPLETED";

  /** Second execution ID test constant. */
  private static final String EXEC_ID_789 = "exec-789";

  /** Duration 5000ms constant. */
  private static final String DURATION_5000MS = "5000ms";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final String executionId = EXEC_ID_456;
    final String status = STATUS_COMPLETED;
    final String duration = DURATION_5000MS;

    // When
    final ExecutionRecord record = new ExecutionRecord(sessionId, executionId, status, duration);

    // Then
    assertThat(record.sessionId()).isEqualTo(sessionId);
    assertThat(record.executionId()).isEqualTo(executionId);
    assertThat(record.status()).isEqualTo(status);
    assertThat(record.duration()).isEqualTo(duration);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final ExecutionRecord record1 =
        new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, DURATION_5000MS);
    final ExecutionRecord record2 =
        new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, DURATION_5000MS);

    // When-Then
    assertThat(record1).isEqualTo(record2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final ExecutionRecord record1 =
        new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, DURATION_5000MS);
    final ExecutionRecord record2 =
        new ExecutionRecord(SESSION_ID_123, EXEC_ID_789, STATUS_COMPLETED, "5000ms");

    // When-Then
    assertThat(record1).isNotEqualTo(record2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final ExecutionRecord record =
        new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, DURATION_5000MS);

    // When
    final String actual = record.toString();

    // Then
    assertThat(actual).contains("ExecutionRecord").contains(SESSION_ID_123);
  }
}
