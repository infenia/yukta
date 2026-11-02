// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowExecutionSummary}. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class WorkflowExecutionSummaryTest {

  @Test
  void recordInstantiation_withAllFields_storesValuesCorrectly() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.executionId()).isEqualTo("exec-1");
    assertThat(summary.workflowId()).isEqualTo("workflow-1");
    assertThat(summary.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(summary.startTime()).isEqualTo(startTime);
    assertThat(summary.endTime()).isEqualTo(endTime);
  }

  @Test
  void recordInstantiation_withNullEndTime_storesNullCorrectly() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", TaskStatus.RUNNING, startTime, null);

    assertThat(summary.executionId()).isEqualTo("exec-1");
    assertThat(summary.workflowId()).isEqualTo("workflow-1");
    assertThat(summary.status()).isEqualTo(TaskStatus.RUNNING);
    assertThat(summary.startTime()).isEqualTo(startTime);
    assertThat(summary.endTime()).isNull();
  }

  @Test
  void recordEquality_sameValues_returnsTrue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary1).isEqualTo(summary2);
  }

  @Test
  void recordEquality_differentExecutionId_returnsFalse() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-2", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentWorkflowId_returnsFalse() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-2", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentStatus_returnsFalse() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.FAILURE, startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentStartTime_returnsFalse() {
    final Instant startTime1 = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant startTime2 = LocalDateTime.of(2026, 6, 25, 10, 1, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime1, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime2, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentEndTime_returnsFalse() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime1 = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final Instant endTime2 = LocalDateTime.of(2026, 6, 25, 10, 6, 0).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime1);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime2);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordHashCode_sameValues_equalHashCodes() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary1.hashCode()).isEqualTo(summary2.hashCode());
  }

  @Test
  void recordHashCode_differentValues_differentHashCodes() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary(
            "exec-2", "workflow-2", TaskStatus.FAILURE, startTime, endTime);

    assertThat(summary1.hashCode()).isNotEqualTo(summary2.hashCode());
  }

  @Test
  void recordToString_withAllFields_containsFieldValues() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    final String str = summary.toString();
    assertThat(str).contains("exec-1");
    assertThat(str).contains("workflow-1");
    assertThat(str).contains("SUCCESS");
  }

  @Test
  void recordToString_withNullEndTime_containsNullValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", TaskStatus.RUNNING, startTime, null);

    final String str = summary.toString();
    assertThat(str).contains("exec-1");
    assertThat(str).contains("workflow-1");
    assertThat(str).contains("RUNNING");
  }

  @Test
  void recordAccessors_getExecutionId_returnsCorrectValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-123", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.executionId()).isEqualTo("exec-123");
  }

  @Test
  void recordAccessors_getWorkflowId_returnsCorrectValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "wf-456", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.workflowId()).isEqualTo("wf-456");
  }

  @Test
  void recordAccessors_getStatus_returnsCorrectValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.status()).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test
  void recordAccessors_getStartTime_returnsCorrectValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.startTime()).isSameAs(startTime);
  }

  @Test
  void recordAccessors_getEndTime_returnsCorrectValue() {
    final Instant startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0).toInstant(ZoneOffset.UTC);
    final Instant endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30).toInstant(ZoneOffset.UTC);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "exec-1", "workflow-1", TaskStatus.SUCCESS, startTime, endTime);

    assertThat(summary.endTime()).isSameAs(endTime);
  }
}
