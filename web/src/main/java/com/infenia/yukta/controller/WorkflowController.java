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

import com.infenia.yukta.dto.request.WorkflowStartRequest;
import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.dto.response.WorkflowStopResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for workflow management API.
 *
 * <p>Provides endpoints for starting and stoping workflow executions and monitoring their progress,
 * including real-time updates via Server-Sent Events.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Workflow API",
    description = "Endpoints for starting and monitoring workflow executions")
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports"})
public class WorkflowController {
  /** The service for managing workflow operations. */
  private final WorkflowService workflowService;

  /** The control bus gateway for managing workflow execution and observability. */
  private final ControlBusGateway controlBus;

  /** The service for managing sessions. */
  private final SessionService sessionService;

  /** HTTP 200 response code constant for Swagger documentation. */
  private static final String HTTP_200 = "200";

  /** Session ID parameter description constant for Swagger documentation. */
  private static final String SESSION_ID_PARAM = "Session ID";

  /** Not Found error message constant. */
  private static final String NOT_FOUND = "Not Found";

  /**
   * Start a workflow execution for a session.
   *
   * @param request the start request containing sessionId and workflowId
   * @return response entity with acknowledgment and execution ID
   */
  @PostMapping("/workflow/start")
  @Operation(
      summary = "Start a workflow",
      description = "Starts the execution of a specific DAG workflow for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "202",
      description = "Workflow start accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "Invalid session ID or workflow ID")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Session or workflow not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> startWorkflow(
      @Valid @RequestBody final WorkflowStartRequest request, final ServerWebExchange exchange) {
    log.atInfo().log(
        "startWorkflow: sessionId={}, workflowId={}", request.sessionId(), request.workflowId());
    return workflowService
        .validateAndStartWorkflow(request.sessionId(), request.workflowId())
        .doOnNext(
            _ ->
                log.atInfo().log(
                    "startWorkflow service call succeeded: sessionId={}, workflowId={}",
                    request.sessionId(),
                    request.workflowId()))
        .map(
            execution ->
                ResponseEntity.accepted()
                    .body(
                        ApiResponse.success(
                            202,
                            "Workflow start accepted",
                            new WorkflowStartResponse(execution.executionId()))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "startWorkflow response sent successfully: sessionId={}, workflowId={}",
                    request.sessionId(),
                    request.workflowId()))
        .onErrorResume(
            e -> {
              log.atError()
                  .log(
                      "startWorkflow error occurred: sessionId={}, workflowId={}, error={}",
                      request.sessionId(),
                      request.workflowId(),
                      e.getMessage());
              @SuppressWarnings("PMD.LawOfDemeter")
              final var req = exchange.getRequest();
              final String path = req.getPath().value();
              final List<ApiResponse.FieldError> errors =
                  List.of(new ApiResponse.FieldError("workflow", e.getMessage()));
              return Mono.just(
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(ApiResponse.error(404, NOT_FOUND, "Workflow not found", path, errors)));
            });
  }

  /**
   * Stop all active workflow executions for a session and workflow.
   *
   * <p>Emits a safe-stop signal for each execution that drains inflight work before terminating.
   * The trigger plugin's stream is also severed, preventing new input from starting new executions.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return response entity with the list of stopped execution IDs
   */
  @PostMapping("/workflow/{sessionId}/{workflowId}/stop")
  @Operation(
      summary = "Stop all workflow executions",
      description =
          "Stops all active workflow executions for a session and workflow, and severs the trigger"
              + " plugin input stream")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Workflow stop signals accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "No active workflow executions found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStopResponse>>> stopWorkflow(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Workflow ID") @PathVariable final String workflowId,
      final ServerWebExchange exchange) {
    log.atInfo().log("stopWorkflow: sessionId={}, workflowId={}", sessionId, workflowId);
    return controlBus
        .stopWorkflow(sessionId, workflowId, "Stopped via REST API")
        .doOnNext(
            executionIds ->
                log.atInfo().log(
                    "stopWorkflow command accepted: sessionId={}, workflowId={}, count={}",
                    sessionId,
                    workflowId,
                    executionIds.size()))
        .map(
            executionIds ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        200,
                        "Workflow stop signals accepted",
                        new WorkflowStopResponse(executionIds))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "stopWorkflow response sent successfully: sessionId={}, workflowId={}",
                    sessionId,
                    workflowId))
        .onErrorResume(
            e -> {
              log.atError()
                  .log(
                      "stopWorkflow error occurred: sessionId={}, workflowId={}, error={}",
                      sessionId,
                      workflowId,
                      e.getMessage());
              @SuppressWarnings("PMD.LawOfDemeter")
              final var req = exchange.getRequest();
              final String path = req.getPath().value();
              final List<ApiResponse.FieldError> errors =
                  List.of(new ApiResponse.FieldError("workflow", e.getMessage()));
              return Mono.just(
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              404, NOT_FOUND, "No active workflow executions", path, errors)));
            });
  }

  /**
   * Stop a specific workflow execution by execution ID.
   *
   * <p>Emits a safe-stop signal that drains inflight work before terminating.
   *
   * @param executionId the execution identifier
   * @return response entity with the stopped execution ID
   */
  @PostMapping("/workflow/executions/{executionId}/stop")
  @Operation(
      summary = "Stop a specific execution",
      description = "Stops a specific workflow execution by execution ID")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Execution stop signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> stopExecution(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      final ServerWebExchange exchange) {
    log.atInfo().log("stopExecution: executionId={}", executionId);
    return controlBus
        .stopExecution(executionId, "Stopped via REST API")
        .doOnNext(
            stoppedId ->
                log.atInfo().log("stopExecution command accepted: executionId={}", stoppedId))
        .map(
            stoppedId ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        200,
                        "Execution stop signal accepted",
                        new WorkflowStartResponse(stoppedId))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "stopExecution response sent successfully: executionId={}", executionId))
        .onErrorResume(
            e -> {
              log.atError()
                  .log(
                      "stopExecution error occurred: executionId={}, error={}",
                      executionId,
                      e.getMessage());
              @SuppressWarnings("PMD.LawOfDemeter")
              final var req = exchange.getRequest();
              final String path = req.getPath().value();
              final List<ApiResponse.FieldError> errors =
                  List.of(new ApiResponse.FieldError("execution", e.getMessage()));
              return Mono.just(
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(404, NOT_FOUND, "Execution not found", path, errors)));
            });
  }

  /**
   * Get the status of a specific workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return response entity with workflow progress
   */
  @GetMapping("/workflow/{sessionId}/status/{executionId}")
  @Operation(
      summary = "Get workflow execution status",
      description = "Retrieves the current status and progress of a specific workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow status retrieved successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowProgress>>> getWorkflowStatus(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      final ServerWebExchange exchange) {
    log.atInfo().log("getWorkflowStatus: sessionId={}, executionId={}", sessionId, executionId);
    return Mono.fromCallable(() -> controlBus.getCurrentProgress(executionId))
        .flatMap(Mono::justOrEmpty)
        .doOnNext(
            _ ->
                log.atInfo().log(
                    "getWorkflowStatus service call succeeded: sessionId={}, executionId={}",
                    sessionId,
                    executionId))
        .map(
            progress ->
                ResponseEntity.ok(
                    ApiResponse.success(200, "Workflow status retrieved successfully", progress)))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "getWorkflowStatus response sent successfully: sessionId={}, executionId={}",
                    sessionId,
                    executionId))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn()
                      .log(
                          "getWorkflowStatus execution not found: sessionId={}, executionId={}",
                          sessionId,
                          executionId);
                  @SuppressWarnings("PMD.LawOfDemeter")
                  final var req = exchange.getRequest();
                  final String path = req.getPath().value();
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError(
                              "executionId", "Execution not found: '" + executionId + "'"));
                  return ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(ApiResponse.error(404, NOT_FOUND, "Execution not found", path, errors));
                }))
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "getWorkflowStatus error occurred: sessionId={}, executionId={}, error={}",
                        sessionId,
                        executionId,
                        error.getMessage()));
  }

  /**
   * Stream status updates for a workflow execution via SSE.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return a flux of status update events
   */
  @GetMapping(
      value = "/workflow/{sessionId}/status/{executionId}/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream workflow execution status",
      description = "Streams the status and progress of a specific workflow execution via SSE")
  public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Include historical status updates (last N minutes)")
          @RequestParam(defaultValue = "true")
          final boolean includeHistory) {
    log.atInfo().log(
        "streamWorkflowStatus: sessionId={}, executionId={}, includeHistory={}",
        sessionId,
        executionId,
        includeHistory);
    return controlBus
        .watchExecution(executionId, includeHistory)
        .doOnNext(
            _ ->
                log.atDebug().log(
                    "streamWorkflowStatus progress received: sessionId={}, executionId={}",
                    sessionId,
                    executionId))
        .map(progress -> ServerSentEvent.<WorkflowProgress>builder().data(progress).build())
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "streamWorkflowStatus error occurred: sessionId={}, executionId={},"
                            + " error={}",
                        sessionId,
                        executionId,
                        error.getMessage()))
        .doOnComplete(
            () ->
                log.atInfo().log(
                    "streamWorkflowStatus stream completed: sessionId={}, executionId={}",
                    sessionId,
                    executionId));
  }

  /**
   * Get history of workflow executions for a session.
   *
   * @param sessionId the session identifier
   * @return response entity with list of execution summaries
   */
  @GetMapping("/workflow/{sessionId}/history")
  @Operation(
      summary = "Get workflow history",
      description = "Retrieves the history of all workflow executions for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow history retrieved successfully")
  public Mono<ResponseEntity<ApiResponse<List<WorkflowExecutionSummary>>>> getWorkflowHistory(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      final ServerWebExchange exchange) {
    log.atInfo().log("getWorkflowHistory: sessionId={}", sessionId);
    return sessionService
        .getSessionConfig(sessionId)
        .doOnNext(
            _ ->
                log.atInfo().log(
                    "getWorkflowHistory session config retrieved: sessionId={}", sessionId))
        .flatMap(
            ignored ->
                Mono.fromCallable(() -> controlBus.getHistory(sessionId))
                    .map(
                        history ->
                            ResponseEntity.ok(
                                ApiResponse.success(
                                    200, "Workflow history retrieved successfully", history))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "getWorkflowHistory response sent successfully: sessionId={}", sessionId))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn().log("getWorkflowHistory session not found: sessionId={}", sessionId);
                  @SuppressWarnings("PMD.LawOfDemeter")
                  final var req = exchange.getRequest();
                  final String path = req.getPath().value();
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError(
                              "sessionId", "Session not found: '" + sessionId + "'"));
                  return ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(ApiResponse.error(404, NOT_FOUND, "Session not found", path, errors));
                }))
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "getWorkflowHistory error occurred: sessionId={}, error={}",
                        sessionId,
                        error.getMessage()));
  }
}
