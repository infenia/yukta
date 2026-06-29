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
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowExecutionSummary}. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class WorkflowExecutionSummaryTest {

  @Test
  void recordInstantiation_withAllFields_storesValuesCorrectly() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary.executionId()).isEqualTo("exec-1");
    assertThat(summary.workflowId()).isEqualTo("workflow-1");
    assertThat(summary.status()).isEqualTo("COMPLETED");
    assertThat(summary.startTime()).isEqualTo(startTime);
    assertThat(summary.endTime()).isEqualTo(endTime);
  }

  @Test
  void recordInstantiation_withNullEndTime_storesNullCorrectly() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "RUNNING", startTime, null);

    assertThat(summary.executionId()).isEqualTo("exec-1");
    assertThat(summary.workflowId()).isEqualTo("workflow-1");
    assertThat(summary.status()).isEqualTo("RUNNING");
    assertThat(summary.startTime()).isEqualTo(startTime);
    assertThat(summary.endTime()).isNull();
  }

  @Test
  void recordEquality_sameValues_returnsTrue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary1).isEqualTo(summary2);
  }

  @Test
  void recordEquality_differentExecutionId_returnsFalse() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-2", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentWorkflowId_returnsFalse() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-2", "COMPLETED", startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentStatus_returnsFalse() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "FAILED", startTime, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentStartTime_returnsFalse() {
    final LocalDateTime startTime1 = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime startTime2 = LocalDateTime.of(2026, 6, 25, 10, 1, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime1, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime2, endTime);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordEquality_differentEndTime_returnsFalse() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime1 = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final LocalDateTime endTime2 = LocalDateTime.of(2026, 6, 25, 10, 6, 0);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime1);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime2);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void recordHashCode_sameValues_equalHashCodes() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary1.hashCode()).isEqualTo(summary2.hashCode());
  }

  @Test
  void recordHashCode_differentValues_differentHashCodes() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);

    final WorkflowExecutionSummary summary1 =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final WorkflowExecutionSummary summary2 =
        new WorkflowExecutionSummary("exec-2", "workflow-2", "FAILED", startTime, endTime);

    assertThat(summary1.hashCode()).isNotEqualTo(summary2.hashCode());
  }

  @Test
  void recordToString_withAllFields_containsFieldValues() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    final String str = summary.toString();
    assertThat(str).contains("exec-1");
    assertThat(str).contains("workflow-1");
    assertThat(str).contains("COMPLETED");
  }

  @Test
  void recordToString_withNullEndTime_containsNullValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "RUNNING", startTime, null);

    final String str = summary.toString();
    assertThat(str).contains("exec-1");
    assertThat(str).contains("workflow-1");
    assertThat(str).contains("RUNNING");
  }

  @Test
  void recordAccessors_getExecutionId_returnsCorrectValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-123", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary.executionId()).isEqualTo("exec-123");
  }

  @Test
  void recordAccessors_getWorkflowId_returnsCorrectValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "wf-456", "COMPLETED", startTime, endTime);

    assertThat(summary.workflowId()).isEqualTo("wf-456");
  }

  @Test
  void recordAccessors_getStatus_returnsCorrectValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary.status()).isEqualTo("COMPLETED");
  }

  @Test
  void recordAccessors_getStartTime_returnsCorrectValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary.startTime()).isSameAs(startTime);
  }

  @Test
  void recordAccessors_getEndTime_returnsCorrectValue() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 6, 25, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 6, 25, 10, 5, 30);
    final WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("exec-1", "workflow-1", "COMPLETED", startTime, endTime);

    assertThat(summary.endTime()).isSameAs(endTime);
  }
}
