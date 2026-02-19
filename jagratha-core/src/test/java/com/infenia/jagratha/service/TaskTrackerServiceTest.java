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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.jagratha.model.WorkflowProgress;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class TaskTrackerServiceTest {

  @Test
  void testWorkflowTracking() {
    TaskTrackerService service = new TaskTrackerService();
    String sessionId = "sess-1";
    List<String> tasks = List.of("task1", "task2");

    service.startWorkflow(sessionId, tasks);

    WorkflowProgress progress = service.getProgress(sessionId);
    assertNotNull(progress);
    assertEquals("RUNNING", progress.status());
    assertEquals(2, progress.tasks().size());

    service.updateTaskStatus(sessionId, "task1", "mod1", "RUNNING");

    Flux<String> logStream = service.getLogStream(sessionId);
    StepVerifier.create(logStream)
        .then(() -> service.appendLog(sessionId, "log line"))
        .expectNext("log line")
        .thenCancel()
        .verify();

    service.finishWorkflow(sessionId, "SUCCESS");
    assertTrue(service.getActiveSessions().contains(sessionId));
    service.removeSession(sessionId);
    assertNull(service.getProgress(sessionId));
  }

  @Test
  void testStreamsWithNoSession() {
    TaskTrackerService service = new TaskTrackerService();
    StepVerifier.create(service.getLogStream("none")).expectComplete().verify();
    StepVerifier.create(service.getStatusStream("none")).expectComplete().verify();
  }
}
