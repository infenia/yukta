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
package com.infenia.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.jagratha.model.WorkflowProgress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class TaskTrackerServiceTest {

  private TaskTrackerService tracker;

  @BeforeEach
  void setUp() {
    tracker = new TaskTrackerService();
    tracker.init();
  }

  @Test
  void testWorkflowTracking() throws InterruptedException {
    String sessionId = "sess-1";
    String workflowId = "wf-1";
    String executionId = "exec-1";
    List<String> nodes = List.of("node1", "node2");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();
    WorkflowProgress progress = tracker.getProgress(sessionId, executionId);

    assertNotNull(progress);
    assertEquals("RUNNING", progress.status());
    assertEquals(2, progress.tasks().size());
    assertEquals("node1", progress.tasks().get(0).nodeId());
    assertEquals("PENDING", progress.tasks().get(0).status());

    tracker.emitTaskStatusEvent(executionId, "node1", "moduleA", "SUCCESS", java.util.Map.of());
    // Wait for async processing
    Thread.sleep(200);

    progress = tracker.getProgress(sessionId, executionId);
    assertEquals("SUCCESS", progress.tasks().get(0).status());
    assertEquals("moduleA", progress.tasks().get(0).module());

    tracker.emitWorkflowStatusEvent(executionId, "COMPLETED");
    // Wait for async processing
    Thread.sleep(200);

    progress = tracker.getProgress(sessionId, executionId);
    assertEquals("COMPLETED", progress.status());
    assertNotNull(progress.endTime());
  }

  @Test
  void testLogStreaming() {
    String sessionId = "sess-1";
    String workflowId = "wf-1";
    String executionId = "exec-log-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    StepVerifier.create(tracker.getLogStream(executionId))
        .then(() -> tracker.emitLogEvent(executionId, "log line 1"))
        .expectNext("log line 1")
        .thenCancel()
        .verify();
  }

  @Test
  void testStatusStreaming() {
    String sessionId = "sess-1";
    String workflowId = "wf-1";
    String executionId = "exec-status-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of("n1")))
        .verifyComplete();

    StepVerifier.create(tracker.getStatusStream(executionId))
        .then(
            () ->
                tracker.emitTaskStatusEvent(
                    executionId, "n1", "mod", "SUCCESS", java.util.Map.of()))
        .assertNext(progress -> assertEquals("wf-1", progress.workflowId()))
        .thenCancel()
        .verify();
  }

  @Test
  void testRemoveSession() {
    String sessionId = "sess-1";
    String workflowId = "wf-1";
    String executionId = "exec-remove-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();
    assertEquals(1, tracker.getActiveSessions().size());
    tracker.removeSession(sessionId);
    assertEquals(0, tracker.getActiveSessions().size());
  }
}
