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
package com.infenia.jagratha;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.controller.AppController;
import com.infenia.jagratha.mapper.AppConfigMapper;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowExecution;
import com.infenia.jagratha.model.WorkflowTriggerRequest;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import com.infenia.jagratha.service.WorkflowService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(AppController.class)
class AppControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private WorkflowService workflowService;
  @MockitoBean private SessionService sessionService;
  @MockitoBean private LogRetrievalService logRetrievalService;
  @MockitoBean private TaskTrackerService trackerService;
  @MockitoBean private AppConfigMapper configMapper;

  @Test
  void testTriggerWorkflowSuccess() {
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("session-1", "w1", Map.of());
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");
    String executionId = "exec-123";
    WorkflowExecution execution = new WorkflowExecution(executionId, Mono.just(response));

    when(workflowService.runWorkflow(anyString(), anyString(), any())).thenReturn(execution);

    webTestClient
        .post()
        .uri("/api/workflow/trigger")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(202)
        .jsonPath("$.message")
        .isEqualTo("Workflow trigger accepted")
        .jsonPath("$.data.executionId")
        .isEqualTo(executionId);
  }

  @Test
  void testUpdateConfig() {
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "desc", List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    ConfigRequest request =
        new ConfigRequest(
            "session-1", "desc", "initiator-1", Map.of(), "/new/path", Map.of("w1", workflow));

    when(sessionService.applyConfigOverrides(any())).thenReturn(Mono.empty());

    webTestClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(200)
        .jsonPath("$.message")
        .isEqualTo("Configuration updated successfully");

    verify(configMapper).toData(any());
    verify(sessionService).applyConfigOverrides(any());
  }
}
