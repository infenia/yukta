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

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class DefaultWorkflowExecutionProviderTest {

  private DefaultWorkflowExecutionProvider provider;
  private WorkflowService workflowService;
  private SessionService sessionService;
  private TaskTrackerService trackerService;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    sessionService = mock(SessionService.class);
    trackerService = mock(TaskTrackerService.class);
    objectMapper = new ObjectMapper();
    provider =
        new DefaultWorkflowExecutionProvider(
            workflowService, sessionService, trackerService, objectMapper);
  }

  @Test
  void testGetWorkflowDetails() {
    WorkflowDefinition def = mock(WorkflowDefinition.class);
    when(sessionService.getSessionWorkflow("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(provider.getWorkflowDetails("s1", "w1")).expectNext(def).verifyComplete();
  }

  @Test
  void testTriggerWorkflowWithPayload() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.runWorkflow(eq("sess-1"), eq("wf-1"), anyMap())).thenReturn(execution);

    StepVerifier.create(provider.triggerWorkflow("sess-1", "wf-1", "{\"key\":\"val\"}"))
        .expectNext("exec-1")
        .verifyComplete();
  }

  @Test
  void testTriggerWorkflowNoPayload() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.runWorkflow(eq("sess-1"), eq("wf-1"), anyMap())).thenReturn(execution);

    StepVerifier.create(provider.triggerWorkflow("sess-1", "wf-1", null))
        .expectNext("exec-1")
        .verifyComplete();
  }

  @Test
  void testTriggerWorkflowBlankPayload() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.runWorkflow(eq("sess-1"), eq("wf-1"), anyMap())).thenReturn(execution);

    StepVerifier.create(provider.triggerWorkflow("sess-1", "wf-1", ""))
        .expectNext("exec-1")
        .verifyComplete();

    verify(workflowService).runWorkflow(eq("sess-1"), eq("wf-1"), eq(Map.of()));
  }

  @Test
  void testTriggerWorkflowWhitespacePayload() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.runWorkflow(eq("sess-1"), eq("wf-1"), anyMap())).thenReturn(execution);

    StepVerifier.create(provider.triggerWorkflow("sess-1", "wf-1", "   "))
        .expectNext("exec-1")
        .verifyComplete();

    verify(workflowService).runWorkflow(eq("sess-1"), eq("wf-1"), eq(Map.of()));
  }

  @Test
  void testTriggerWorkflowInvalidJson() {
    StepVerifier.create(provider.triggerWorkflow("sess-1", "wf-1", "invalid-json"))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void testGetWorkflowStatus() {
    WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "e1", "w1", "COMPLETED", LocalDateTime.now(), LocalDateTime.now());
    when(trackerService.getHistory("sess-1")).thenReturn(List.of(summary));

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectNext(summary)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(trackerService.getHistory("sess-1")).thenReturn(List.of());

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1")).verifyComplete();
  }
}
