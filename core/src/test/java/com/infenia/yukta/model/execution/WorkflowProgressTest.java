// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link WorkflowProgress}. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class WorkflowProgressTest {

  @Test
  void testWorkflowProgressCreation() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress.executionId()).isEqualTo("exec-1");
    assertThat(progress.sessionId()).isEqualTo("session-1");
    assertThat(progress.workflowId()).isEqualTo("workflow-1");
    assertThat(progress.status()).isEqualTo("COMPLETED");
    assertThat(progress.startTime()).isEqualTo(startTime);
    assertThat(progress.endTime()).isEqualTo(endTime);
    assertThat(progress.tasks()).isNotNull().isEmpty();
  }

  @Test
  void testWorkflowProgressWithTasks() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final TaskProgress task1 =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    final TaskProgress task2 =
        new TaskProgress("node-2", "module-2", "RUNNING", startTime, null, null);
    final List<TaskProgress> tasks = List.of(task1, task2);

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, endTime);

    assertThat(progress.tasks()).hasSize(2).contains(task1, task2);
  }

  @Test
  void testWorkflowProgressTasksImmutability() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final TaskProgress task1 =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    final List<TaskProgress> originalTasks = List.of(task1);

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", originalTasks, startTime, endTime);

    assertThat(progress.tasks()).hasSize(1);
    assertThatThrownBy(() -> progress.tasks().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testWorkflowProgressWithNullEndTime() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, null);

    assertThat(progress.status()).isEqualTo("RUNNING");
    assertThat(progress.endTime()).isNull();
    assertThat(progress.startTime()).isNotNull();
  }

  @Test
  void testWorkflowProgressWithNullStartTime() {
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress("exec-2", "session-2", "workflow-2", "PENDING", tasks, null, endTime);

    assertThat(progress.status()).isEqualTo("PENDING");
    assertThat(progress.startTime()).isNull();
    assertThat(progress.endTime()).isNotNull();
  }

  @Test
  void testWorkflowProgressWithBothNullTimes() {
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress("exec-3", "session-3", "workflow-3", "QUEUED", tasks, null, null);

    assertThat(progress.status()).isEqualTo("QUEUED");
    assertThat(progress.startTime()).isNull();
    assertThat(progress.endTime()).isNull();
  }

  @Test
  void testWorkflowProgressStatus() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress inProgress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, null);
    final WorkflowProgress completed =
        new WorkflowProgress(
            "exec-2", "session-2", "workflow-2", "COMPLETED", tasks, startTime, endTime);
    final WorkflowProgress failed =
        new WorkflowProgress(
            "exec-3", "session-3", "workflow-3", "FAILED", tasks, startTime, endTime);

    assertThat(inProgress.status()).isEqualTo("RUNNING");
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(failed.status()).isEqualTo("FAILED");
  }

  @Test
  void testWorkflowProgressIdentifiers() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-xyz", "session-abc", "workflow-123", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress.executionId()).isEqualTo("exec-xyz");
    assertThat(progress.sessionId()).isEqualTo("session-abc");
    assertThat(progress.workflowId()).isEqualTo("workflow-123");
  }

  @Test
  void testWorkflowProgressWithMultipleTasks() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final LocalDateTime time1 = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime time2 = LocalDateTime.of(2026, 5, 30, 10, 1, 0);
    final LocalDateTime time3 = LocalDateTime.of(2026, 5, 30, 10, 2, 0);
    final LocalDateTime time4 = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final TaskProgress task1 =
        new TaskProgress("node-1", "module-1", "COMPLETED", time1, time2, null);
    final TaskProgress task2 =
        new TaskProgress("node-2", "module-2", "COMPLETED", time2, time3, null);
    final TaskProgress task3 =
        new TaskProgress("node-3", "module-3", "COMPLETED", time3, time4, null);
    final List<TaskProgress> tasks = List.of(task1, task2, task3);

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress.tasks())
        .hasSize(3)
        .extracting(TaskProgress::nodeId)
        .containsExactly("node-1", "node-2", "node-3");
  }

  @Test
  void testWorkflowProgressEquality() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);
    final WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress1).isEqualTo(progress2);
  }

  @Test
  void testWorkflowProgressHashCode() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);
    final WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress1.hashCode()).isEqualTo(progress2.hashCode());
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testWorkflowProgressToString() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final List<TaskProgress> tasks = List.of();

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    final String toString = progress.toString();
    assertThat(toString).isNotNull().contains("exec-1", "session-1", "workflow-1");
  }

  @Test
  void testWorkflowProgressRecord() {
    final LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    final LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    final TaskProgress task =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    final List<TaskProgress> tasks = List.of(task);

    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertThat(progress)
        .isNotNull()
        .extracting(
            WorkflowProgress::executionId,
            WorkflowProgress::sessionId,
            WorkflowProgress::workflowId,
            WorkflowProgress::status,
            p -> p.tasks().size(),
            WorkflowProgress::startTime,
            WorkflowProgress::endTime)
        .containsExactly("exec-1", "session-1", "workflow-1", "COMPLETED", 1, startTime, endTime);
  }
}
