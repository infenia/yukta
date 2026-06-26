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
package com.infenia.yukta.service.workflow;

import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

  @Mock private ControlBusGateway controlBus;

  private WorkflowService workflowService;

  @BeforeEach
  void setUp() {
    workflowService = new WorkflowService(controlBus);
  }

  @Test
  void testValidateAndStartWorkflowSuccess() {
    String sessionId = "sess-123";
    String workflowId = "wf-456";
    String executionId = "exec-789";

    when(controlBus.startWorkflow(sessionId, workflowId)).thenReturn(Mono.just(executionId));

    StepVerifier.create(workflowService.validateAndStartWorkflow(sessionId, workflowId))
        .assertNext(
            execution -> {
              assert execution.executionId().equals(executionId);
              assert execution.result() != null;
            })
        .verifyComplete();
  }

  @Test
  void testValidateAndStartWorkflowNotFound() {
    String sessionId = "sess-missing";
    String workflowId = "wf-missing";

    when(controlBus.startWorkflow(sessionId, workflowId))
        .thenReturn(
            Mono.error(
                new IllegalArgumentException(
                    "Workflow not found for session: " + sessionId + ", workflow: " + workflowId)));

    StepVerifier.create(workflowService.validateAndStartWorkflow(sessionId, workflowId))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Workflow not found"))
        .verify();
  }

  @Test
  void testValidateAndStartWorkflowReturnsExecutionWithId() {
    String sessionId = "sess-exec";
    String workflowId = "wf-exec";
    String executionId = "exec-001";

    when(controlBus.startWorkflow(sessionId, workflowId)).thenReturn(Mono.just(executionId));

    WorkflowExecution execution =
        workflowService.validateAndStartWorkflow(sessionId, workflowId).block();

    assert execution != null;
    assert execution.executionId().equals(executionId);
  }
}
