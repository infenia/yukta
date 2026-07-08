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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for WorkflowController. */
@ExtendWith(OutputCaptureExtension.class)
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.TooManyMethods"})
class WorkflowControllerTest {

  /** API endpoint for workflow start. */
  private static final String API_WORKFLOW_START = "/api/workflow/start";

  /** Success message for workflow start. */
  private static final String WORKFLOW_START_ACCEPTED = "Workflow start accepted";

  /** Error message when workflow not found. */
  private static final String WORKFLOW_NOT_FOUND = "Workflow not found";

  /** Test session identifier. */
  private static final String SESSION_ID_1 = "session-1";

  /** Test execution identifier. */
  private static final String EXEC_ID_1 = "exec-1";

  /** Test session ID. */
  private static final String SESS_ID_1 = "sess-1";

  /** Test workflow ID. */
  private static final String WF_ID_1 = "wf-1";

  /** Running status string. */
  private static final String RUNNING = "RUNNING";

  /** Status endpoint path. */
  private static final String STATUS_ENDPOINT = "/api/workflow/sess-1/status/exec-1";

  /** History endpoint path. */
  private static final String HISTORY_ENDPOINT = "/api/workflow/sess-1/history";

  /** Stop endpoint path. */
  private static final String STOP_ENDPOINT = "/api/workflow/sess-1/wf-1/stop";

  /** Stopped message. */
  private static final String STOPPED_VIA_REST_API = "Stopped via REST API";

  /** Success message for retrieving workflow history. */
  private static final String WORKFLOW_HISTORY_RETRIEVED =
      "Workflow history retrieved successfully";

  /** Error message when session not found. */
  private static final String SESSION_NOT_FOUND = "Session not found";

  /** JSONPath expression for status field. */
  private static final String DOLLAR_STATUS = "$.status";

  /** JSONPath expression for message field. */
  private static final String DOLLAR_MESSAGE = "$.message";

  /** JSONPath expression for execution ID field. */
  private static final String DOLLAR_DATA_EXECUTION_ID = "$.data.executionId";

  /** Execution ID prefix for log messages. */
  private static final String COMMA_EXEC_ID = ", executionId=";

  /** Stream endpoint suffix. */
  private static final String STREAM = "/stream";

  /** Workflow execution suffix. */
  private static final String API_WORKFLOW_EXECUTIONS = "/api/workflow/executions/";

  /** Stop endpoint. */
  private static final String STOP = "/stop";

  /** Execution not found message. */
  private static final String EXECUTION_NOT_FOUND = "Execution not found";

  /** Error message when node not found. */
  private static final String NODE_NOT_FOUND = "Node not found";

  /** Error message prefix used when mocking a node-not-found gateway failure. */
  private static final String NODE_NOT_FOUND_PREFIX = "Node not found: ";

  /** Pause endpoint path. */
  private static final String PAUSE_ENDPOINT = "/api/workflow/sess-1/exec-1/pause";

  /** Resume endpoint path. */
  private static final String RESUME_ENDPOINT = "/api/workflow/sess-1/exec-1/resume";

  /** Pause accepted message. */
  private static final String PAUSE_ACCEPTED = "Workflow pause signal accepted";

  /** Resume accepted message. */
  private static final String RESUME_ACCEPTED = "Workflow resume signal accepted";

  /** Test node identifier. */
  private static final String NODE_ID_1 = "node-1";

  /** Pause node endpoint path. */
  private static final String PAUSE_NODE_ENDPOINT = "/api/workflow/sess-1/exec-1/node/node-1/pause";

  /** Resume node endpoint path. */
  private static final String RESUME_NODE_ENDPOINT =
      "/api/workflow/sess-1/exec-1/node/node-1/resume";

  /** Pause node accepted message. */
  private static final String NODE_PAUSE_ACCEPTED = "Node pause signal accepted";

  /** Resume node accepted message. */
  private static final String NODE_RESUME_ACCEPTED = "Node resume signal accepted";

  /** Stop node endpoint path. */
  private static final String STOP_NODE_ENDPOINT =
      "/api/workflow/sess-1/exec-1/node/node-1/stop?immediate=true&reason=test-reason";

  /** Reason used in stop node requests. */
  private static final String STOP_NODE_REASON = "test-reason";

  /** Skip node endpoint path. */
  private static final String SKIP_NODE_ENDPOINT =
      "/api/workflow/sess-1/exec-1/node/node-1/skip?skip=true";

  /** Stop node accepted message. */
  private static final String NODE_STOP_ACCEPTED = "Node stop signal accepted";

  /** Skip node accepted message. */
  private static final String NODE_SKIP_ACCEPTED = "Node skip signal accepted";

  /** Session identifier used to simulate a session-ownership mismatch. */
  private static final String OTHER_SESSION = "other-session";

  /** Error message used to simulate a non-404 control bus failure. */
  private static final String CONTROL_BUS_FAILURE = "Control bus failure";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webClient;

  /** Mock service for workflow operations. */
  private WorkflowService workflowService;

  /** Mock gateway for control bus operations. */
  private ControlBusGateway controlBusGateway;

  /** Mock service for session operations. */
  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    controlBusGateway = mock(ControlBusGateway.class);
    sessionService = mock(SessionService.class);
    final WorkflowController controller =
        new WorkflowController(workflowService, controlBusGateway, sessionService);
    webClient = WebTestClient.bindToController(controller).build();
  }

  // --- Trigger Tests ---

  @Test
  void testTriggerWorkflowSuccess() {
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_1, "w1");
    final TaskResponse response = new TaskResponse("SUCCESS", "Build successful");
    final String executionId = "exec-123";
    final WorkflowExecution execution = new WorkflowExecution(executionId, Mono.just(response));

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.just(execution));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_START)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(202)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(WORKFLOW_START_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(executionId)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(202);
  }

  @Test
  void testTriggerWorkflowSuccessLogging(final CapturedOutput output) {
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_1, "w1");
    final TaskResponse response = new TaskResponse("SUCCESS", "Build successful");
    final String executionId = "exec-123";
    final WorkflowExecution execution = new WorkflowExecution(executionId, Mono.just(response));

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.just(execution));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_START)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isAccepted()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(202);

    assertThat(output.toString())
        .contains("startWorkflow: sessionId=" + SESSION_ID_1 + ", workflowId=w1")
        .contains("startWorkflow service call succeeded")
        .contains("startWorkflow response sent successfully");
  }

  @Test
  void testTriggerWorkflowError() {
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_1, "w1");

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException(WORKFLOW_NOT_FOUND)));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_START)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(WORKFLOW_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testTriggerWorkflowErrorLogging(final CapturedOutput output) {
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_1, "w1");

    when(workflowService.validateAndStartWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException(WORKFLOW_NOT_FOUND)));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_START)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);

    assertThat(output.toString())
        .contains("startWorkflow: sessionId=" + SESSION_ID_1 + ", workflowId=w1")
        .contains("startWorkflow error occurred")
        .contains(WORKFLOW_NOT_FOUND);
  }

  // --- Status Tests ---

  @Test
  void testGetWorkflowStatus() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .get()
            .uri(STATUS_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetWorkflowStatusLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient.get().uri(STATUS_ENDPOINT).exchange().expectStatus().isOk().returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=" + SESS_ID_1 + COMMA_EXEC_ID + EXEC_ID_1)
        .contains("getWorkflowStatus service call succeeded")
        .contains("getWorkflowStatus response sent successfully");
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient.get().uri(STATUS_ENDPOINT).exchange().expectStatus().isNotFound().returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetWorkflowStatusNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient.get().uri(STATUS_ENDPOINT).exchange().expectStatus().isNotFound().returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=" + SESS_ID_1 + COMMA_EXEC_ID + EXEC_ID_1)
        .contains("getWorkflowStatus execution not found");
  }

  @Test
  void testGetWorkflowStatusError(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1))
        .thenThrow(new RuntimeException("Control bus error"));

    webClient.get().uri(STATUS_ENDPOINT).exchange().expectStatus().is5xxServerError();

    assertThat(output.toString())
        .contains("getWorkflowStatus: sessionId=" + SESS_ID_1 + COMMA_EXEC_ID + EXEC_ID_1)
        .contains("getWorkflowStatus error occurred");
  }

  @Test
  void testStreamWorkflowStatus() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.watchExecution(EXEC_ID_1, true)).thenReturn(Flux.just(progress));

    final var result =
        webClient
            .get()
            .uri(STATUS_ENDPOINT + STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStreamWorkflowStatusLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.watchExecution(EXEC_ID_1, true)).thenReturn(Flux.just(progress));

    final var result =
        webClient
            .get()
            .uri(STATUS_ENDPOINT + STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);

    assertThat(output.toString())
        .contains("streamWorkflowStatus: sessionId=" + SESS_ID_1 + COMMA_EXEC_ID + EXEC_ID_1)
        .contains("streamWorkflowStatus stream completed");
  }

  @Test
  void testStreamWorkflowStatusWithMultipleProgressUpdates() {
    final WorkflowProgress progress1 =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    final WorkflowProgress progress2 =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            "COMPLETED",
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.watchExecution(EXEC_ID_1, true))
        .thenReturn(Flux.just(progress1, progress2));

    final var result =
        webClient
            .get()
            .uri(STATUS_ENDPOINT + STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStreamWorkflowStatusError(final CapturedOutput output) {
    when(controlBusGateway.watchExecution(EXEC_ID_1, true))
        .thenReturn(Flux.error(new RuntimeException("Stream error")));

    final var result =
        webClient
            .get()
            .uri(STATUS_ENDPOINT + STREAM)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);

    assertThat(output.toString())
        .contains("streamWorkflowStatus: sessionId=" + SESS_ID_1 + COMMA_EXEC_ID + EXEC_ID_1)
        .contains("streamWorkflowStatus error occurred");
  }

  @Test
  void testGetWorkflowHistory() {
    when(sessionService.getSessionConfig(SESS_ID_1)).thenReturn(Mono.just(Map.of()));
    when(controlBusGateway.getHistory(SESS_ID_1)).thenReturn(List.of());

    final var result =
        webClient
            .get()
            .uri(HISTORY_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(WORKFLOW_HISTORY_RETRIEVED)
            .jsonPath("$.data")
            .isArray()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetWorkflowHistoryLogging(final CapturedOutput output) {
    when(sessionService.getSessionConfig(SESS_ID_1)).thenReturn(Mono.just(Map.of()));
    when(controlBusGateway.getHistory(SESS_ID_1)).thenReturn(List.of());

    final var result =
        webClient.get().uri(HISTORY_ENDPOINT).exchange().expectStatus().isOk().returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=" + SESS_ID_1)
        .contains("getWorkflowHistory session config retrieved")
        .contains("getWorkflowHistory response sent successfully");
  }

  @Test
  void testGetWorkflowHistorySessionNotFound() {
    when(sessionService.getSessionConfig(SESS_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .get()
            .uri(HISTORY_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(SESSION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetWorkflowHistorySessionNotFoundLogging(final CapturedOutput output) {
    when(sessionService.getSessionConfig(SESS_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient.get().uri(HISTORY_ENDPOINT).exchange().expectStatus().isNotFound().returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=" + SESS_ID_1)
        .contains("getWorkflowHistory session not found");
  }

  @Test
  void testGetWorkflowHistoryError(final CapturedOutput output) {
    when(sessionService.getSessionConfig(SESS_ID_1))
        .thenReturn(Mono.error(new RuntimeException("Session service error")));

    webClient.get().uri(HISTORY_ENDPOINT).exchange().expectStatus().is5xxServerError();

    assertThat(output.toString())
        .contains("getWorkflowHistory: sessionId=" + SESS_ID_1)
        .contains("getWorkflowHistory error occurred");
  }

  // --- Stop Tests ---

  @Test
  void testStopWorkflowSuccess() {
    when(controlBusGateway.stopWorkflow(SESS_ID_1, WF_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.just(List.of("exec-456", "exec-457")));

    final var result =
        webClient
            .post()
            .uri(STOP_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo("Workflow stop signals accepted")
            .jsonPath("$.data.executionIds[0]")
            .isEqualTo("exec-456")
            .jsonPath("$.data.executionIds[1]")
            .isEqualTo("exec-457")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStopWorkflowSuccessLogging(final CapturedOutput output) {
    when(controlBusGateway.stopWorkflow(SESS_ID_1, WF_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.just(List.of("exec-456")));

    webClient.post().uri(STOP_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("stopWorkflow: sessionId=" + SESS_ID_1 + ", workflowId=" + WF_ID_1)
        .contains("stopWorkflow command accepted")
        .contains("stopWorkflow response sent successfully");
  }

  @Test
  void testStopWorkflowNotFound() {
    when(controlBusGateway.stopWorkflow(SESS_ID_1, WF_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.error(new IllegalArgumentException("No active execution found")));

    final var result =
        webClient
            .post()
            .uri(STOP_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo("No active workflow executions")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStopWorkflowNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.stopWorkflow(SESS_ID_1, WF_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.error(new IllegalArgumentException("No active execution found")));

    webClient.post().uri(STOP_ENDPOINT).exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("stopWorkflow: sessionId=" + SESS_ID_1 + ", workflowId=" + WF_ID_1)
        .contains("stopWorkflow error occurred")
        .contains("No active execution found");
  }

  // --- Stop Execution Tests ---

  @Test
  void testStopExecutionSuccess() {
    when(controlBusGateway.stopExecution(EXEC_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.just(EXEC_ID_1));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_EXECUTIONS + EXEC_ID_1 + STOP)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo("Execution stop signal accepted")
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStopExecutionSuccessLogging(final CapturedOutput output) {
    when(controlBusGateway.stopExecution(EXEC_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.just(EXEC_ID_1));

    webClient
        .post()
        .uri(API_WORKFLOW_EXECUTIONS + EXEC_ID_1 + STOP)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(output.toString())
        .contains("stopExecution: executionId=" + EXEC_ID_1)
        .contains("stopExecution command accepted")
        .contains("stopExecution response sent successfully");
  }

  @Test
  void testStopExecutionNotFound() {
    when(controlBusGateway.stopExecution(EXEC_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.error(new IllegalArgumentException(EXECUTION_NOT_FOUND)));

    final var result =
        webClient
            .post()
            .uri(API_WORKFLOW_EXECUTIONS + EXEC_ID_1 + STOP)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStopExecutionNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.stopExecution(EXEC_ID_1, STOPPED_VIA_REST_API))
        .thenReturn(Mono.error(new IllegalArgumentException(EXECUTION_NOT_FOUND)));

    webClient
        .post()
        .uri(API_WORKFLOW_EXECUTIONS + EXEC_ID_1 + STOP)
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(output.toString())
        .contains("stopExecution: executionId=" + EXEC_ID_1)
        .contains("stopExecution error occurred")
        .contains(EXECUTION_NOT_FOUND);
  }

  // --- Pause Workflow Tests ---

  @Test
  void testPauseWorkflowSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseWorkflow(EXEC_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(PAUSE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(PAUSE_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testPauseWorkflowSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseWorkflow(EXEC_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(PAUSE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("pauseWorkflow: executionId=" + EXEC_ID_1)
        .contains("pauseWorkflow command accepted")
        .contains("pauseWorkflow response sent successfully");
  }

  @Test
  void testPauseWorkflowNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(PAUSE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testPauseWorkflowNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    webClient.post().uri(PAUSE_ENDPOINT).exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("pauseWorkflow: executionId=" + EXEC_ID_1)
        .contains("pauseWorkflow error occurred")
        .contains(EXECUTION_NOT_FOUND);
  }

  @Test
  void testPauseWorkflowSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(PAUSE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testPauseWorkflowNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseWorkflow(EXEC_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(PAUSE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Resume Workflow Tests ---

  @Test
  void testResumeWorkflowSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeWorkflow(EXEC_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(RESUME_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(RESUME_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testResumeWorkflowSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeWorkflow(EXEC_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(RESUME_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("resumeWorkflow: executionId=" + EXEC_ID_1)
        .contains("resumeWorkflow command accepted")
        .contains("resumeWorkflow response sent successfully");
  }

  @Test
  void testResumeWorkflowNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(RESUME_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testResumeWorkflowNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    webClient.post().uri(RESUME_ENDPOINT).exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("resumeWorkflow: executionId=" + EXEC_ID_1)
        .contains("resumeWorkflow error occurred")
        .contains(EXECUTION_NOT_FOUND);
  }

  @Test
  void testResumeWorkflowSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(RESUME_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testResumeWorkflowNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeWorkflow(EXEC_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(RESUME_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Pause Node Tests ---

  @Test
  void testPauseNodeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(PAUSE_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_PAUSE_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testPauseNodeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(PAUSE_NODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("pauseNode: executionId=" + EXEC_ID_1)
        .contains("pauseNode command accepted")
        .contains("pauseNode response sent successfully");
  }

  @Test
  void testPauseNodeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(PAUSE_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testPauseNodeExecutionNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    webClient.post().uri(PAUSE_NODE_ENDPOINT).exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("pauseNode: executionId=" + EXEC_ID_1)
        .contains("pauseNode error occurred")
        .contains(EXECUTION_NOT_FOUND);
  }

  @Test
  void testPauseNodeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(PAUSE_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testPauseNodeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(PAUSE_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testPauseNodeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.pauseNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(PAUSE_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Resume Node Tests ---

  @Test
  void testResumeNodeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(RESUME_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_RESUME_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testResumeNodeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeNode(EXEC_ID_1, NODE_ID_1)).thenReturn(Mono.empty());

    webClient.post().uri(RESUME_NODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("resumeNode: executionId=" + EXEC_ID_1)
        .contains("resumeNode command accepted")
        .contains("resumeNode response sent successfully");
  }

  @Test
  void testResumeNodeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(RESUME_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testResumeNodeExecutionNotFoundLogging(final CapturedOutput output) {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    webClient.post().uri(RESUME_NODE_ENDPOINT).exchange().expectStatus().isNotFound();

    assertThat(output.toString())
        .contains("resumeNode: executionId=" + EXEC_ID_1)
        .contains("resumeNode error occurred")
        .contains(EXECUTION_NOT_FOUND);
  }

  @Test
  void testResumeNodeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(RESUME_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testResumeNodeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(RESUME_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testResumeNodeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.resumeNode(EXEC_ID_1, NODE_ID_1))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(RESUME_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Stop Node Tests ---

  @Test
  void testStopNodeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stopNode(EXEC_ID_1, NODE_ID_1, true, STOP_NODE_REASON))
        .thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(STOP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_STOP_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStopNodeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stopNode(EXEC_ID_1, NODE_ID_1, true, STOP_NODE_REASON))
        .thenReturn(Mono.empty());

    webClient.post().uri(STOP_NODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("stopNode: executionId=" + EXEC_ID_1)
        .contains("stopNode command accepted")
        .contains("stopNode response sent successfully");
  }

  @Test
  void testStopNodeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(STOP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStopNodeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stopNode(EXEC_ID_1, NODE_ID_1, true, STOP_NODE_REASON))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(STOP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStopNodeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(STOP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testStopNodeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.stopNode(EXEC_ID_1, NODE_ID_1, true, STOP_NODE_REASON))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(STOP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  // --- Skip Node Tests ---

  @Test
  void testSkipNodeSuccess() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.skipNode(EXEC_ID_1, NODE_ID_1, true)).thenReturn(Mono.empty());

    final var result =
        webClient
            .post()
            .uri(SKIP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_SKIP_ACCEPTED)
            .jsonPath(DOLLAR_DATA_EXECUTION_ID)
            .isEqualTo(EXEC_ID_1)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testSkipNodeSuccessLogging(final CapturedOutput output) {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.skipNode(EXEC_ID_1, NODE_ID_1, true)).thenReturn(Mono.empty());

    webClient.post().uri(SKIP_NODE_ENDPOINT).exchange().expectStatus().isOk();

    assertThat(output.toString())
        .contains("skipNode: executionId=" + EXEC_ID_1)
        .contains("skipNode command accepted")
        .contains("skipNode response sent successfully");
  }

  @Test
  void testSkipNodeExecutionNotFound() {
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(null);

    final var result =
        webClient
            .post()
            .uri(SKIP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testSkipNodeNodeNotFound() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.skipNode(EXEC_ID_1, NODE_ID_1, true))
        .thenReturn(Mono.error(new IllegalArgumentException(NODE_NOT_FOUND_PREFIX + NODE_ID_1)));

    final var result =
        webClient
            .post()
            .uri(SKIP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(NODE_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testSkipNodeSessionMismatch() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            OTHER_SESSION,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);

    final var result =
        webClient
            .post()
            .uri(SKIP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .isNotFound()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(404)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(EXECUTION_NOT_FOUND)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testSkipNodeNonNotFoundErrorPropagates() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            EXEC_ID_1,
            SESS_ID_1,
            WF_ID_1,
            RUNNING,
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBusGateway.getCurrentProgress(EXEC_ID_1)).thenReturn(progress);
    when(controlBusGateway.skipNode(EXEC_ID_1, NODE_ID_1, true))
        .thenReturn(Mono.error(new RuntimeException(CONTROL_BUS_FAILURE)));

    final var result =
        webClient
            .post()
            .uri(SKIP_NODE_ENDPOINT)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }
}
