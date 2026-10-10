// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.WorkflowStartRequest;
import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.dto.response.WorkflowStopResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Starting workflows, stopping all runs of one, and listing a session's runs. Errors are not caught
 * here: core throws {@link NotFoundException} and the global handler picks the status.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(
    name = "Workflow API",
    description = "Endpoints for starting workflows and listing their executions")
public class WorkflowController {

  /** Session ID parameter description. */
  private static final String SESSION_ID = "Session ID";

  /** The workflow service. */
  private final WorkflowService workflowService;

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The session service. */
  private final SessionService sessionService;

  /**
   * Start a workflow.
   *
   * @param request session, workflow and optional trigger payload
   * @param idempotencyKey optional key that makes retries return the original execution
   * @return the new execution ID
   */
  @PostMapping("/workflow/start")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary = "Start a workflow",
      description =
          "Starts the execution of a DAG workflow for a session. Answers 404 for an unknown"
              + " session or workflow and 429 when the execution limit is reached.")
  public Mono<ApiResponse<WorkflowStartResponse>> startWorkflow(
      @Valid @RequestBody final WorkflowStartRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey) {
    final Mono<com.infenia.yukta.model.workflow.WorkflowExecution> started =
        idempotencyKey == null
            ? workflowService.validateAndStartWorkflow(
                request.sessionId(), request.workflowId(), request.payload())
            : workflowService.validateAndStartWorkflow(
                request.sessionId(), request.workflowId(), request.payload(), idempotencyKey);
    return started.map(
        execution ->
            ApiResponse.success(
                202,
                "Workflow start accepted",
                new WorkflowStartResponse(execution.executionId())));
  }

  /**
   * Stop every active execution of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the IDs of the executions that were signalled
   */
  @PostMapping("/workflow/{sessionId}/{workflowId}/stop")
  @Operation(
      summary = "Stop all workflow executions",
      description =
          "Stops all active executions of a workflow and severs the trigger plugin input stream")
  public Mono<ApiResponse<WorkflowStopResponse>> stopWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = "Workflow ID") @PathVariable final String workflowId) {
    return controlBus
        .stopWorkflow(sessionId, workflowId, "Stopped via REST API")
        .map(
            ids ->
                ApiResponse.success(
                    200, "Workflow stop signals accepted", new WorkflowStopResponse(ids)));
  }

  /**
   * List a session's executions, newest first.
   *
   * @param sessionId the session
   * @param limit page size (default 500, at most 1000)
   * @param cursor execution ID of the last item of the previous page
   * @return one page of execution summaries
   */
  @GetMapping("/workflow/{sessionId}/history")
  @Operation(
      summary = "Get workflow history",
      description =
          "Lists the executions of a session, newest first. A further page is announced in the"
              + " X-Next-Cursor header.")
  public Mono<ResponseEntity<ApiResponse<List<WorkflowExecutionSummary>>>> getWorkflowHistory(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @RequestParam(required = false) final Integer limit,
      @RequestParam(required = false) final String cursor) {
    return sessionService
        .getSessionConfig(sessionId)
        .switchIfEmpty(Mono.error(() -> new NotFoundException("Session not found: " + sessionId)))
        .flatMap(config -> controlBus.getHistory(sessionId))
        .map(
            history ->
                Pagination.page(
                    history,
                    limit,
                    cursor,
                    WorkflowExecutionSummary::executionId,
                    "Workflow history retrieved successfully"));
  }
}
