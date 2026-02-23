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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.PreparedWorkflow;
import com.infenia.jagratha.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

  @Mock private AppConfigService configService;
  @Mock private WorkflowOrchestrator orchestrator;

  private WorkflowService workflowService;

  @BeforeEach
  void setUp() {
    workflowService = new WorkflowService(configService, orchestrator);
  }

  @Test
  void testRunWorkflowSuccess() {
    String sessionId = "sess-success";
    String workflowId = "w-success";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    PreparedWorkflow prepared = new PreparedWorkflow(def, Map.of(), Map.of(), Map.of(), List.of());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), any(), any())).thenReturn(Mono.empty());

    StepVerifier.create(workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()))
        .expectNextMatches(res -> "SUCCESS".equals(res.status()))
        .verifyComplete();
  }

  @Test
  void testRunWorkflowNoWorkflow() {
    String sessionId = "sess-none";
    String workflowId = "w-none";

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.empty());

    StepVerifier.create(workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()))
        .expectNextMatches(
            res ->
                "FAILURE".equals(res.status()) && res.output().contains("No workflow configured"))
        .verifyComplete();
  }

  @Test
  void testRunWorkflowError() {
    String sessionId = "sess-error";
    String workflowId = "w-error";
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());

    when(configService.getWorkflow(sessionId, workflowId)).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.error(new RuntimeException("Fail")));

    StepVerifier.create(workflowService.runWorkflow(sessionId, workflowId, java.util.Map.of()))
        .expectNextMatches(
            res -> "FAILURE".equals(res.status()) && res.output().contains("Workflow failed: Fail"))
        .verifyComplete();
  }
}
