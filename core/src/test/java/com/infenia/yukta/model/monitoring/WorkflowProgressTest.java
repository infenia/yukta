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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowProgressTest {

  @Test
  void testWorkflowProgressCreation() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertEquals("exec-1", progress.executionId());
    assertEquals("session-1", progress.sessionId());
    assertEquals("workflow-1", progress.workflowId());
    assertEquals("COMPLETED", progress.status());
    assertEquals(startTime, progress.startTime());
    assertEquals(endTime, progress.endTime());
    assertNotNull(progress.tasks());
    assertTrue(progress.tasks().isEmpty());
  }

  @Test
  void testWorkflowProgressWithTasks() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    TaskProgress task1 =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    TaskProgress task2 = new TaskProgress("node-2", "module-2", "RUNNING", startTime, null, null);
    List<TaskProgress> tasks = List.of(task1, task2);

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, endTime);

    assertEquals(2, progress.tasks().size());
    assertTrue(progress.tasks().contains(task1));
    assertTrue(progress.tasks().contains(task2));
  }

  @Test
  void testWorkflowProgressTasksImmutability() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    TaskProgress task1 =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    List<TaskProgress> originalTasks = List.of(task1);

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", originalTasks, startTime, endTime);

    assertEquals(1, progress.tasks().size());

    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> progress.tasks().add(null));
  }

  @Test
  void testWorkflowProgressWithNullEndTime() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, null);

    assertEquals("RUNNING", progress.status());
    assertNull(progress.endTime());
    assertNotNull(progress.startTime());
  }

  @Test
  void testWorkflowProgressWithNullStartTime() {
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress("exec-2", "session-2", "workflow-2", "PENDING", tasks, null, endTime);

    assertEquals("PENDING", progress.status());
    assertNull(progress.startTime());
    assertNotNull(progress.endTime());
  }

  @Test
  void testWorkflowProgressWithBothNullTimes() {
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress("exec-3", "session-3", "workflow-3", "QUEUED", tasks, null, null);

    assertEquals("QUEUED", progress.status());
    assertNull(progress.startTime());
    assertNull(progress.endTime());
  }

  @Test
  void testWorkflowProgressStatus() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress inProgress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "RUNNING", tasks, startTime, null);
    WorkflowProgress completed =
        new WorkflowProgress(
            "exec-2", "session-2", "workflow-2", "COMPLETED", tasks, startTime, endTime);
    WorkflowProgress failed =
        new WorkflowProgress(
            "exec-3", "session-3", "workflow-3", "FAILED", tasks, startTime, endTime);

    assertEquals("RUNNING", inProgress.status());
    assertEquals("COMPLETED", completed.status());
    assertEquals("FAILED", failed.status());
  }

  @Test
  void testWorkflowProgressIdentifiers() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-xyz", "session-abc", "workflow-123", "COMPLETED", tasks, startTime, endTime);

    assertEquals("exec-xyz", progress.executionId());
    assertEquals("session-abc", progress.sessionId());
    assertEquals("workflow-123", progress.workflowId());
  }

  @Test
  void testWorkflowProgressWithMultipleTasks() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    LocalDateTime time1 = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime time2 = LocalDateTime.of(2026, 5, 30, 10, 1, 0);
    LocalDateTime time3 = LocalDateTime.of(2026, 5, 30, 10, 2, 0);
    LocalDateTime time4 = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    TaskProgress task1 = new TaskProgress("node-1", "module-1", "COMPLETED", time1, time2, null);
    TaskProgress task2 = new TaskProgress("node-2", "module-2", "COMPLETED", time2, time3, null);
    TaskProgress task3 = new TaskProgress("node-3", "module-3", "COMPLETED", time3, time4, null);
    List<TaskProgress> tasks = List.of(task1, task2, task3);

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertEquals(3, progress.tasks().size());
    assertEquals("node-1", progress.tasks().get(0).nodeId());
    assertEquals("node-2", progress.tasks().get(1).nodeId());
    assertEquals("node-3", progress.tasks().get(2).nodeId());
  }

  @Test
  void testWorkflowProgressEquality() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);
    WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertEquals(progress1, progress2);
  }

  @Test
  void testWorkflowProgressHashCode() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);
    WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertEquals(progress1.hashCode(), progress2.hashCode());
  }

  @Test
  void testWorkflowProgressToString() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    List<TaskProgress> tasks = List.of();

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    String toString = progress.toString();
    assertNotNull(toString);
    assertTrue(toString.contains("exec-1"));
    assertTrue(toString.contains("session-1"));
    assertTrue(toString.contains("workflow-1"));
  }

  @Test
  void testWorkflowProgressRecord() {
    LocalDateTime startTime = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 5, 30, 10, 5, 0);
    TaskProgress task =
        new TaskProgress("node-1", "module-1", "COMPLETED", startTime, endTime, null);
    List<TaskProgress> tasks = List.of(task);

    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "session-1", "workflow-1", "COMPLETED", tasks, startTime, endTime);

    assertNotNull(progress);
    assertEquals("exec-1", progress.executionId());
    assertEquals("session-1", progress.sessionId());
    assertEquals("workflow-1", progress.workflowId());
    assertEquals("COMPLETED", progress.status());
    assertEquals(1, progress.tasks().size());
    assertEquals(startTime, progress.startTime());
    assertEquals(endTime, progress.endTime());
  }
}
