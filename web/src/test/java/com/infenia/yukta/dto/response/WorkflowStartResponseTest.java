// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowStartResponseTest. */
@NoArgsConstructor
class WorkflowStartResponseTest {

  /** Execution ID UUID constant for testing. */
  private static final String EXEC_ID_UUID = "550e8400-e29b-41d4-a716-446655440000";

  /** Different execution ID UUID constant for testing. */
  private static final String EXEC_ID_UUID_DIFFERENT = "550e8400-e29b-41d4-a716-446655440001";

  @Test
  void constructor_validInput_createsRecord() {
    // Given
    final String executionId = EXEC_ID_UUID;

    // When
    final WorkflowStartResponse response = new WorkflowStartResponse(executionId);

    // Then
    assertThat(response.executionId()).isEqualTo(executionId);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final WorkflowStartResponse response1 = new WorkflowStartResponse(EXEC_ID_UUID);
    final WorkflowStartResponse response2 = new WorkflowStartResponse(EXEC_ID_UUID);

    // When-Then
    assertThat(response1).isEqualTo(response2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final WorkflowStartResponse response1 = new WorkflowStartResponse(EXEC_ID_UUID);
    final WorkflowStartResponse response2 = new WorkflowStartResponse(EXEC_ID_UUID_DIFFERENT);

    // When-Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final String executionId = EXEC_ID_UUID;
    final WorkflowStartResponse response = new WorkflowStartResponse(executionId);

    // When
    final String actual = response.toString();

    // Then
    assertThat(actual).contains("WorkflowStartResponse").contains(executionId);
  }
}
