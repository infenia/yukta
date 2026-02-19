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
package com.infenia.jagratha.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private WorkflowService workflowService;
  private LogRetrievalService logRetrievalService;

  @BeforeEach
  void setUp() {
    workflowService = Mockito.mock(WorkflowService.class);
    logRetrievalService = Mockito.mock(LogRetrievalService.class);
    mcpTools = new AppMcpTools(workflowService, logRetrievalService);
  }

  @Test
  void testGetProjectStatus() {
    assertEquals(
        "Jagratha is managing the project and ready to run quality checks.",
        mcpTools.getProjectStatus());
  }

  @Test
  void testTriggerQualityChecksSuccess() {
    TaskResponse response = new TaskResponse("SUCCESS", "Done");
    when(workflowService.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.triggerQualityChecks())
        .expectNext("Status: SUCCESS\n\nOutput:\nDone")
        .verifyComplete();
  }

  @Test
  void testTriggerQualityChecksFailure() {
    TaskResponse response = new TaskResponse("FAILURE", "Error");
    when(workflowService.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.triggerQualityChecks())
        .expectNext("Status: FAILURE\n\nOutput:\nError")
        .verifyComplete();
  }
}
