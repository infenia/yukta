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
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.api.WorkflowTriggerRequest;
import com.infenia.yukta.model.monitoring.WorkflowProgress;
import com.infenia.yukta.model.session.TaskResponse;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.LogRetrievalService;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(AppController.class)
class AppControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private WorkflowService workflowService;
  @MockitoBean private LogRetrievalService logRetrievalService;
  @MockitoBean private TaskTrackerService trackerService;

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
  void testGetWorkflowStatus() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(trackerService.getProgress("sess-1", "exec-1")).thenReturn(progress);

    webTestClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("exec-1");
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(trackerService.getProgress("sess-1", "exec-1")).thenReturn(null);

    webTestClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testStreamWorkflowStatus() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(trackerService.getStatusStream("exec-1")).thenReturn(Flux.just(progress));

    webTestClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }

  @Test
  void testGetWorkflowHistory() {
    when(trackerService.getHistory("sess-1")).thenReturn(List.of());

    webTestClient.get().uri("/api/workflow/sess-1/history").exchange().expectStatus().isOk();
  }

  @Test
  void testListLogs() {
    when(logRetrievalService.listLogs("sess-1")).thenReturn(Mono.just(List.of("test.log")));

    webTestClient
        .get()
        .uri("/api/logs/sess-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("test.log");
  }

  @Test
  void testGetLogContent() {
    when(logRetrievalService.getLogContent("sess-1", "test.log")).thenReturn(Mono.just("content"));

    webTestClient
        .get()
        .uri("/api/logs/sess-1/test.log")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data")
        .isEqualTo("content");
  }

  @Test
  void testGetLogContentNotFound() {
    when(logRetrievalService.getLogContent("sess-1", "test.log"))
        .thenReturn(Mono.error(new java.io.IOException()));

    webTestClient.get().uri("/api/logs/sess-1/test.log").exchange().expectStatus().isNotFound();
  }

  @Test
  void testGetRawLogContent() {
    when(logRetrievalService.getLogContent("sess-1", "test.log")).thenReturn(Mono.just("content"));

    webTestClient
        .get()
        .uri("/api/logs/sess-1/test.log/raw")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("content");
  }

  @Test
  void testGetRawLogContentNotFound() {
    when(logRetrievalService.getLogContent("sess-1", "test.log"))
        .thenReturn(Mono.error(new java.io.IOException()));

    webTestClient.get().uri("/api/logs/sess-1/test.log/raw").exchange().expectStatus().isNotFound();
  }
}
