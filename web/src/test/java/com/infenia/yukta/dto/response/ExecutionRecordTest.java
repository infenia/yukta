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

import org.junit.jupiter.api.Test;

class ExecutionRecordTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String sessionId = "session-123";
    String executionId = "exec-456";
    String status = "COMPLETED";
    String duration = "5000ms";

    // When
    ExecutionRecord record = new ExecutionRecord(sessionId, executionId, status, duration);

    // Then
    assertThat(record.sessionId()).isEqualTo(sessionId);
    assertThat(record.executionId()).isEqualTo(executionId);
    assertThat(record.status()).isEqualTo(status);
    assertThat(record.duration()).isEqualTo(duration);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    ExecutionRecord record1 = new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms");
    ExecutionRecord record2 = new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms");

    // When-Then
    assertThat(record1).isEqualTo(record2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    ExecutionRecord record1 = new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms");
    ExecutionRecord record2 = new ExecutionRecord("session-123", "exec-789", "COMPLETED", "5000ms");

    // When-Then
    assertThat(record1).isNotEqualTo(record2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    ExecutionRecord record = new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms");

    // When
    String actual = record.toString();

    // Then
    assertThat(actual).contains("ExecutionRecord").contains("session-123");
  }
}
