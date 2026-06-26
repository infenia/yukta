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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.request.WorkflowStartRequest;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.session.TaskResponse;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
class WorkflowControllerTest {

  private WebTestClient webClient;
  private WorkflowService workflowService;
  private ControlBusGateway controlBusGateway;
  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    controlBusGateway = mock(ControlBusGateway.class);
    sessionService = mock(SessionService.class);
    WorkflowController controller =
        new WorkflowController(workflowService, controlBusGateway, sessionService);
    webClient = WebTestClient.bindToController(controller).build();
  }

  // --- Trigger Tests ---

  @Test
  void testTriggerWorkflowSuccess() {
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "w1");
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");
    String executionId = "exec-123";
    WorkflowExecution execution = new WorkflowExecution(executionId, Mono.just(response));

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.just(execution));

    webClient
        .post()
        .uri("/api/workflow/start")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(202)
        .jsonPath("$.message")
        .isEqualTo("Workflow start accepted")
        .jsonPath("$.data.executionId")
        .isEqualTo(executionId);
  }

  @Test
  void testTriggerWorkflowSuccessLogging(CapturedOutput output) {
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "w1");
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");
    String executionId = "exec-123";
    WorkflowExecution execution = new WorkflowExecution(executionId, Mono.just(response));

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.just(execution));

    webClient
        .post()
        .uri("/api/workflow/start")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isAccepted();

    assertThat(output.toString())
        .contains("startWorkflow: sessionId=session-1, workflowId=w1")
        .contains("startWorkflow service call succeeded")
        .contains("startWorkflow response sent successfully");
  }

  @Test
  void testTriggerWorkflowError() {
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "w1");

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException("Workflow not found")));

    webClient
        .post()
        .uri("/api/workflow/start")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404)
        .jsonPath("$.message")
        .isEqualTo("Workflow not found");
  }

  @Test
  void testTriggerWorkflowErrorLogging(CapturedOutput output) {
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "w1");

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException("Workflow not found")));

    webClient
        .post()
        .uri("/api/workflow/start")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(output.toString())
        .contains("startWorkflow: sessionId=session-1, workflowId=w1")
        .contains("startWorkflow error occurred")
        .contains("Workflow not found");
  }

  // --- Status Tests ---

  @Test
  void testGetWorkflowStatus() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(controlBusGateway.getCurrentProgress("exec-1")).thenReturn(progress);

    webClient
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
  void testGetWorkflowStatusLogging(CapturedOutput output) {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(controlBusGateway.getCurrentProgress("exec-1")).thenReturn(progress);

    webClient.get().uri("/api/workflow/sess-1/status/exec-1").exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=sess-1, executionId=exec-1")
        .contains("getWorkflowStatus service call succeeded")
        .contains("getWorkflowStatus response sent successfully");
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(controlBusGateway.getCurrentProgress("exec-1")).thenReturn(null);

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testGetWorkflowStatusNotFoundLogging(CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress("exec-1")).thenReturn(null);

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1")
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=sess-1, executionId=exec-1")
        .contains("getWorkflowStatus execution not found");
  }

  @Test
  void testGetWorkflowStatusError(CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress("exec-1"))
        .thenThrow(new RuntimeException("Control bus error"));

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1")
        .exchange()
        .expectStatus()
        .is5xxServerError();

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=sess-1, executionId=exec-1")
        .contains("getWorkflowStatus error occurred");
  }

  @Test
  void testStreamWorkflowStatus() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(controlBusGateway.watchExecution("exec-1")).thenReturn(Flux.just(progress));

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }

  @Test
  void testStreamWorkflowStatusLogging(CapturedOutput output) {
    WorkflowProgress progress =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    when(controlBusGateway.watchExecution("exec-1")).thenReturn(Flux.just(progress));

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1/stream")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(output.toString())
        .contains("streamWorkflowStatus: sessionId=sess-1, executionId=exec-1")
        .contains("streamWorkflowStatus stream completed");
  }

  @Test
  void testStreamWorkflowStatusWithMultipleProgressUpdates() {
    WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "RUNNING", List.of(), LocalDateTime.now(), null);
    WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec-1", "sess-1", "wf-1", "COMPLETED", List.of(), LocalDateTime.now(), null);
    when(controlBusGateway.watchExecution("exec-1")).thenReturn(Flux.just(progress1, progress2));

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }

  @Test
  void testStreamWorkflowStatusError(CapturedOutput output) {
    when(controlBusGateway.watchExecution("exec-1"))
        .thenReturn(Flux.error(new RuntimeException("Stream error")));

    webClient
        .get()
        .uri("/api/workflow/sess-1/status/exec-1/stream")
        .exchange()
        .expectStatus()
        .is5xxServerError();

    assertThat(output.toString())
        .contains("streamWorkflowStatus: sessionId=sess-1, executionId=exec-1")
        .contains("streamWorkflowStatus error occurred");
  }

  @Test
  void testGetWorkflowHistory() {
    when(sessionService.getSessionConfig("sess-1")).thenReturn(Mono.just(Map.of()));
    when(controlBusGateway.getHistory("sess-1")).thenReturn(List.of());

    webClient
        .get()
        .uri("/api/workflow/sess-1/history")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(200)
        .jsonPath("$.message")
        .isEqualTo("Workflow history retrieved successfully")
        .jsonPath("$.data")
        .isArray();
  }

  @Test
  void testGetWorkflowHistoryLogging(CapturedOutput output) {
    when(sessionService.getSessionConfig("sess-1")).thenReturn(Mono.just(Map.of()));
    when(controlBusGateway.getHistory("sess-1")).thenReturn(List.of());

    webClient.get().uri("/api/workflow/sess-1/history").exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=sess-1")
        .contains("getWorkflowHistory session config retrieved")
        .contains("getWorkflowHistory response sent successfully");
  }

  @Test
  void testGetWorkflowHistorySessionNotFound() {
    when(sessionService.getSessionConfig("sess-1")).thenReturn(Mono.empty());

    webClient
        .get()
        .uri("/api/workflow/sess-1/history")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404)
        .jsonPath("$.message")
        .isEqualTo("Session not found");
  }

  @Test
  void testGetWorkflowHistorySessionNotFoundLogging(CapturedOutput output) {
    when(sessionService.getSessionConfig("sess-1")).thenReturn(Mono.empty());

    webClient.get().uri("/api/workflow/sess-1/history").exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=sess-1")
        .contains("getWorkflowHistory session not found");
  }

  @Test
  void testGetWorkflowHistoryError(CapturedOutput output) {
    when(sessionService.getSessionConfig("sess-1"))
        .thenReturn(Mono.error(new RuntimeException("Session service error")));

    webClient
        .get()
        .uri("/api/workflow/sess-1/history")
        .exchange()
        .expectStatus()
        .is5xxServerError();

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=sess-1")
        .contains("getWorkflowHistory error occurred");
  }

  // --- Stop Tests ---

  @Test
  void testStopWorkflowSuccess() {
    when(controlBusGateway.stopWorkflow("sess-1", "wf-1", "Stopped via REST API"))
        .thenReturn(Mono.just("exec-456"));

    webClient
        .post()
        .uri("/api/workflow/sess-1/wf-1/stop")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(200)
        .jsonPath("$.message")
        .isEqualTo("Workflow stop signal accepted")
        .jsonPath("$.data.executionId")
        .isEqualTo("exec-456");
  }

  @Test
  void testStopWorkflowSuccessLogging(CapturedOutput output) {
    when(controlBusGateway.stopWorkflow("sess-1", "wf-1", "Stopped via REST API"))
        .thenReturn(Mono.just("exec-456"));

    webClient.post().uri("/api/workflow/sess-1/wf-1/stop").exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("stopWorkflow: sessionId=sess-1, workflowId=wf-1")
        .contains("stopWorkflow command accepted")
        .contains("stopWorkflow response sent successfully");
  }

  @Test
  void testStopWorkflowNotFound() {
    when(controlBusGateway.stopWorkflow("sess-1", "wf-1", "Stopped via REST API"))
        .thenReturn(Mono.error(new IllegalArgumentException("No active execution found")));

    webClient
        .post()
        .uri("/api/workflow/sess-1/wf-1/stop")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(404)
        .jsonPath("$.message")
        .isEqualTo("No active workflow execution");
  }

  @Test
  void testStopWorkflowNotFoundLogging(CapturedOutput output) {
    when(controlBusGateway.stopWorkflow("sess-1", "wf-1", "Stopped via REST API"))
        .thenReturn(Mono.error(new IllegalArgumentException("No active execution found")));

    webClient.post().uri("/api/workflow/sess-1/wf-1/stop").exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("stopWorkflow: sessionId=sess-1, workflowId=wf-1")
        .contains("stopWorkflow error occurred")
        .contains("No active execution found");
  }
}
