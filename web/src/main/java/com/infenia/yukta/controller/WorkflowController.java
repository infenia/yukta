// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
import java.util.function.Supplier;
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
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
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
   * Safely stop the current execution and restart the entire workflow from the beginning using the
   * original trigger payload.
   *
   * @param executionId the execution to restart
   * @return response entity with the new execution ID
   */
  @PostMapping("/workflow/executions/{executionId}/restart")
  @Operation(
      summary = "Restart a workflow execution",
      description =
          "Stops the current execution and restarts the entire workflow from the beginning using"
              + " the original trigger payload")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow restart accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> restartWorkflow(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      final ServerWebExchange exchange) {
    log.atInfo().log("restartWorkflow: executionId={}", executionId);
    return controlBus
        .restartWorkflow(executionId)
        .doOnNext(
            newExecId ->
                log.atInfo().log(
                    "restartWorkflow command accepted: executionId={}, newExecutionId={}",
                    executionId,
                    newExecId))
        .map(
            newExecId ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        200, "Workflow restart accepted", new WorkflowStartResponse(newExecId))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "restartWorkflow response sent successfully: executionId={}", executionId))
        .onErrorResume(
            IllegalArgumentException.class,
            e -> {
              log.atError()
                  .log(
                      "restartWorkflow error occurred: executionId={}, error={}",
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
   * Safely stop the current execution and restart the workflow from a specific node, replaying the
   * last known checkpoints for its parent nodes.
   *
   * @param executionId the execution to restart
   * @param fromNodeId the node from which to resume execution
   * @return response entity with the new execution ID
   */
  @PostMapping("/workflow/executions/{executionId}/restart/{fromNodeId}")
  @Operation(
      summary = "Restart a workflow execution from a node",
      description =
          "Stops the current execution and restarts from a specific node, replaying the last"
              + " known checkpoints for its parent nodes")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow restart from node accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> restartFromNode(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID to restart from") @PathVariable final String fromNodeId,
      final ServerWebExchange exchange) {
    log.atInfo().log("restartFromNode: executionId={}, fromNodeId={}", executionId, fromNodeId);
    return controlBus
        .restartFromNode(executionId, fromNodeId)
        .doOnNext(
            newExecId ->
                log.atInfo().log(
                    "restartFromNode command accepted: executionId={}, fromNodeId={},"
                        + " newExecutionId={}",
                    executionId,
                    fromNodeId,
                    newExecId))
        .map(
            newExecId ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        200,
                        "Workflow restart from node accepted",
                        new WorkflowStartResponse(newExecId))))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "restartFromNode response sent successfully: executionId={}, fromNodeId={}",
                    executionId,
                    fromNodeId))
        .onErrorResume(
            IllegalArgumentException.class,
            e -> {
              log.atError()
                  .log(
                      "restartFromNode error occurred: executionId={}, fromNodeId={}, error={}",
                      executionId,
                      fromNodeId,
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
   * Run a session-scoped control signal against an execution (and optionally a single node),
   * sharing the session-ownership check, logging, and 404-on-{@link IllegalArgumentException}
   * handling common to the pause/resume workflow and node endpoints.
   *
   * @param operationName the operation name used as the log message prefix
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to target, or {@code null} for a workflow-level operation
   * @param successMessage the message returned in the success response body
   * @param action supplies the control-bus call to invoke once session ownership is confirmed
   * @param exchange the current server exchange, used to build the error response path
   * @return response entity with the targeted execution ID
   */
  private Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> executeControlSignal(
      final String operationName,
      final String sessionId,
      final String executionId,
      final String nodeId,
      final String successMessage,
      final Supplier<Mono<Void>> action,
      final ServerWebExchange exchange) {
    final String idContext =
        nodeId == null
            ? "executionId=" + executionId
            : "executionId=" + executionId + ", nodeId=" + nodeId;
    log.atInfo().log("{}: {}, sessionId={}", operationName, idContext, sessionId);
    return Mono.fromCallable(() -> controlBus.getCurrentProgress(executionId))
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Execution not found: " + executionId)))
        .flatMap(
            progress ->
                progress.sessionId().equals(sessionId)
                    ? action.get()
                    : Mono.<Void>error(
                        new IllegalArgumentException("Execution not found: " + executionId)))
        .doOnSuccess(_ -> log.atInfo().log("{} command accepted: {}", operationName, idContext))
        .thenReturn(
            ResponseEntity.ok(
                ApiResponse.success(200, successMessage, new WorkflowStartResponse(executionId))))
        .doOnSuccess(
            _ -> log.atInfo().log("{} response sent successfully: {}", operationName, idContext))
        .onErrorResume(
            IllegalArgumentException.class,
            e -> {
              log.atError()
                  .log("{} error occurred: {}, error={}", operationName, idContext, e.getMessage());
              @SuppressWarnings("PMD.LawOfDemeter")
              final var req = exchange.getRequest();
              final String path = req.getPath().value();
              final boolean nodeNotFound = e.getMessage().startsWith("Node not found");
              final String field = nodeNotFound ? "node" : "execution";
              final String topMessage = nodeNotFound ? "Node not found" : "Execution not found";
              final List<ApiResponse.FieldError> errors =
                  List.of(new ApiResponse.FieldError(field, e.getMessage()));
              return Mono.just(
                  ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(ApiResponse.error(404, NOT_FOUND, topMessage, path, errors)));
            });
  }

  /**
   * Pause a workflow execution.
   *
   * <p>Applies global backpressure so the execution stops pulling new elements while inflight work
   * continues to completion.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to pause
   * @return response entity with the paused execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/pause")
  @Operation(
      summary = "Pause a workflow execution",
      description = "Pauses a specific workflow execution via global backpressure")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow pause signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> pauseWorkflow(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "pauseWorkflow",
        sessionId,
        executionId,
        null,
        "Workflow pause signal accepted",
        () -> controlBus.pauseWorkflow(executionId),
        exchange);
  }

  /**
   * Resume a paused workflow execution.
   *
   * <p>Restores normal backpressure-driven element processing.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to resume
   * @return response entity with the resumed execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/resume")
  @Operation(
      summary = "Resume a workflow execution",
      description = "Resumes a specific paused workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow resume signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> resumeWorkflow(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "resumeWorkflow",
        sessionId,
        executionId,
        null,
        "Workflow resume signal accepted",
        () -> controlBus.resumeWorkflow(executionId),
        exchange);
  }

  /**
   * Pause a single node within a workflow execution via backpressure.
   *
   * <p>The target node stops pulling elements but inflight work continues to completion.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to pause
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/pause")
  @Operation(
      summary = "Pause a node",
      description = "Pauses a single node in a workflow execution via backpressure")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node pause signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> pauseNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "pauseNode",
        sessionId,
        executionId,
        nodeId,
        "Node pause signal accepted",
        () -> controlBus.pauseNode(executionId, nodeId),
        exchange);
  }

  /**
   * Resume a paused node within a workflow execution.
   *
   * <p>The node resumes pulling elements and normal processing.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to resume
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/resume")
  @Operation(
      summary = "Resume a node",
      description = "Resumes a single paused node in a workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node resume signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> resumeNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "resumeNode",
        sessionId,
        executionId,
        nodeId,
        "Node resume signal accepted",
        () -> controlBus.resumeNode(executionId, nodeId),
        exchange);
  }

  /**
   * Stop a single node within a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to stop
   * @param immediate true for hard stop (cancel upstream), false for drain & stop
   * @param reason human-readable explanation for logging
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/stop")
  @Operation(
      summary = "Stop a node",
      description =
          "Stops a single node in a workflow execution, either immediately or after draining"
              + " inflight work")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node stop signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> stopNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      @Parameter(description = "True for hard stop, false for drain & stop")
          @RequestParam(defaultValue = "false")
          final boolean immediate,
      @Parameter(description = "Human-readable reason for the stop")
          @RequestParam(defaultValue = "Stopped via REST API")
          final String reason,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "stopNode",
        sessionId,
        executionId,
        nodeId,
        "Node stop signal accepted",
        () -> controlBus.stopNode(executionId, nodeId, immediate, reason),
        exchange);
  }

  /**
   * Mark a node as skipped or not within a workflow execution.
   *
   * <p>When skipped, the node passes messages through without invoking the processor.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to skip
   * @param skip true to skip, false to unskip
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/skip")
  @Operation(
      summary = "Skip or unskip a node",
      description = "Marks a single node in a workflow execution as skipped (passthrough) or not")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node skip signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> skipNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      @Parameter(description = "True to skip, false to unskip") @RequestParam(defaultValue = "true")
          final boolean skip,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "skipNode",
        sessionId,
        executionId,
        nodeId,
        "Node skip signal accepted",
        () -> controlBus.skipNode(executionId, nodeId, skip),
        exchange);
  }

  /**
   * Enable step-through debug mode on a node within a workflow execution.
   *
   * <p>Each element must be explicitly stepped via {@link #stepNode}. The node automatically pauses
   * until step signals are sent.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to enable step mode on
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step/enable")
  @Operation(
      summary = "Enable step mode on a node",
      description = "Enables step-through debug mode on a single node in a workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Step mode enable signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> enableStepMode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "enableStepMode",
        sessionId,
        executionId,
        nodeId,
        "Step mode enable signal accepted",
        () -> controlBus.enableStepMode(executionId, nodeId),
        exchange);
  }

  /**
   * Disable step-through debug mode on a node within a workflow execution.
   *
   * <p>The node returns to normal pause/resume behavior.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to disable step mode on
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step/disable")
  @Operation(
      summary = "Disable step mode on a node",
      description = "Disables step-through debug mode on a single node in a workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Step mode disable signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> disableStepMode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "disableStepMode",
        sessionId,
        executionId,
        nodeId,
        "Step mode disable signal accepted",
        () -> controlBus.disableStepMode(executionId, nodeId),
        exchange);
  }

  /**
   * Step to the next element on a node that is in step-through mode.
   *
   * <p>Allows exactly one element to pass through the node before blocking again.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to target
   * @param nodeId the node to step
   * @return response entity with the execution ID
   */
  @PostMapping("/workflow/{sessionId}/{executionId}/node/{nodeId}/step")
  @Operation(
      summary = "Step a node",
      description = "Allows exactly one element to pass through a node in step-through mode")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Node step signal accepted")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution or node not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>> stepNode(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      final ServerWebExchange exchange) {
    return executeControlSignal(
        "stepNode",
        sessionId,
        executionId,
        nodeId,
        "Node step signal accepted",
        () -> controlBus.stepNode(executionId, nodeId),
        exchange);
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
