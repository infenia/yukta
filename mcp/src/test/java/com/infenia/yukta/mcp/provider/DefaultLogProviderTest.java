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
package com.infenia.yukta.mcp.provider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class DefaultLogProviderTest {

  private DefaultLogProvider provider;
  private DefaultTaskTrackerService trackerService;

  @BeforeEach
  void setUp() {
    trackerService = mock(DefaultTaskTrackerService.class);
    provider = new DefaultLogProvider(trackerService);
  }

  @Test
  void testStreamSessionLogs() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1));

    StepVerifier.create(provider.streamSessionLogs("sess-1", "w1", "e1", ".*Execution.*"))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testStreamSessionLogsWithFilter() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1));

    StepVerifier.create(provider.streamSessionLogs("sess-1", "other", null, null))
        .expectNextCount(0)
        .verifyComplete();
  }

  @Test
  void testStreamSessionLogsWorkflowIdNull() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    WorkflowExecutionSummary s2 =
        new WorkflowExecutionSummary(
            "e2", "w2", "RUNNING", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1, s2));

    // workflowId=null, executionId="e1" - should match only e1
    StepVerifier.create(provider.streamSessionLogs("sess-1", null, "e1", ".*Execution.*"))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testStreamSessionLogsBothNull() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    WorkflowExecutionSummary s2 =
        new WorkflowExecutionSummary(
            "e2", "w2", "RUNNING", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1, s2));

    // workflowId=null, executionId=null - should match all
    StepVerifier.create(provider.streamSessionLogs("sess-1", null, null, ".*Execution.*"))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  void testStreamSessionLogsInvalidPattern() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1));

    assertThrows(
        IllegalArgumentException.class,
        () -> provider.streamSessionLogs("sess-1", null, null, "[invalid").blockFirst());
  }

  @Test
  void testGetWorkflowExecutionLogs() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1));

    StepVerifier.create(provider.getWorkflowExecutionLogs("sess-1", "e1", ".*COMPLETED.*"))
        .expectNextMatches(log -> log.contains("e1"))
        .verifyComplete();
  }

  @Test
  void testGetWorkflowExecutionLogsNotFound() {
    when(trackerService.getHistory("sess-1")).thenReturn(List.of());

    StepVerifier.create(provider.getWorkflowExecutionLogs("sess-1", "e1", null))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void testGetWorkflowExecutionLogsFilterNoMatch() {
    WorkflowExecutionSummary s1 =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(s1));

    StepVerifier.create(provider.getWorkflowExecutionLogs("sess-1", "e1", "NOT_FOUND"))
        .expectNext("")
        .verifyComplete();
  }
}
