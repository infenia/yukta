// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowExecutions. */
@NoArgsConstructor
class WorkflowExecutionsTest {

  /** Sample execution summary constant. */
  private static final WorkflowExecutionSummary SUMMARY =
      new WorkflowExecutionSummary(
          "exec-091-qp-55",
          "wf-982-xk-11",
          "RUNNING",
          LocalDateTime.of(2026, 7, 26, 14, 22, 1),
          null);

  @Test
  void constructor_validInputs_createsRecord() {
    // Given-When
    final WorkflowExecutions executions = new WorkflowExecutions(List.of(SUMMARY));

    // Then
    assertThat(executions.executions()).containsExactly(SUMMARY);
  }

  @Test
  void constructor_withNullExecutions_convertsToEmptyList() {
    // Given-When
    final WorkflowExecutions executions = new WorkflowExecutions(null);

    // Then
    assertThat(executions.executions()).isEmpty();
    assertThat(executions.executions()).isUnmodifiable();
  }

  @Test
  void executions_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final WorkflowExecutions executions = new WorkflowExecutions(List.of(SUMMARY));

    // When-Then
    assertThatThrownBy(() -> executions.executions().add(SUMMARY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given-When-Then
    assertThat(new WorkflowExecutions(List.of(SUMMARY)))
        .isEqualTo(new WorkflowExecutions(List.of(SUMMARY)));
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given-When
    final String actual = new WorkflowExecutions(List.of(SUMMARY)).toString();

    // Then
    assertThat(actual).contains("WorkflowExecutions").contains("exec-091-qp-55");
  }
}
