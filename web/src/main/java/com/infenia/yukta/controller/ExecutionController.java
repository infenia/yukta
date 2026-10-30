// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Execution lifecycle and status. Each operation lives at {@code /api/executions/{executionId}/…}
 * and, for existing clients, at its legacy {@code /api/workflow/…} path. Legacy paths that name a
 * session also check that the execution belongs to it.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Execution API", description = "Stop, restart and observe workflow executions")
public class ExecutionController {

  /** Execution ID parameter description. */
  private static final String EXECUTION_ID = "Execution ID";

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The access. */
  private final ExecutionAccess access;

  /** Applies a command to a whole tree of runs. */
  private final TreeControl treeControl;

  /** Knows the runs of a tree. */
  private final TaskTrackerService tracker;

  /**
   * Stop one execution.
   *
   * @param executionId the execution
   * @param tree also stop every unfinished run it started as a sub-workflow
   * @param mode how to stop: DRAIN, CANCEL or KILL
   * @return the stopped execution ID
   */
  @PostMapping({"/executions/{executionId}/stop", "/workflow/executions/{executionId}/stop"})
  @Operation(
      summary = "Stop a specific execution",
      description =
          "With tree=true also every unfinished run it started as a sub-workflow. Legacy path:"
              + " /api/workflow/executions/{executionId}/stop (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> stopExecution(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = "Also the runs it started as sub-workflows, at any depth")
          @RequestParam(defaultValue = "false")
          final boolean tree,
      @Parameter(
              description =
                  "DRAIN lets in-flight work finish (default), CANCEL cancels it, KILL stops at"
                      + " once")
          @RequestParam(defaultValue = "DRAIN")
          final StopMode mode) {
    return treeControl
        .apply(executionId, tree, id -> controlBus.stopExecution(id, "Stopped via REST API", mode))
        .thenReturn(started("Execution stop signal accepted", executionId));
  }

  /**
   * Get the tree of runs an execution belongs to below it.
   *
   * @param executionId the run at the top
   * @return the run and every run it started as a sub-workflow, at any depth, oldest first
   */
  @GetMapping("/executions/{executionId}/tree")
  @Operation(
      summary = "Get the tree of runs below an execution",
      description =
          "The execution and every run it started as a sub-workflow, at any depth, oldest first,"
              + " each with its parentExecutionId and status")
  public Mono<ApiResponse<List<WorkflowExecutionSummary>>> getTree(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return access
        .require(null, executionId)
        .map(_ -> ApiResponse.success(200, "Tree retrieved", tracker.getTree(executionId)));
  }

  /**
   * Restart an execution from the beginning.
   *
   * @param executionId the execution
   * @return the new execution ID
   */
  @PostMapping({"/executions/{executionId}/restart", "/workflow/executions/{executionId}/restart"})
  @Operation(
      summary = "Restart a workflow execution",
      description =
          "Stops the execution and restarts the workflow with the original trigger payload."
              + " Legacy path: /api/workflow/executions/{executionId}/restart (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> restartWorkflow(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return controlBus
        .restartWorkflow(executionId)
        .map(id -> started("Workflow restart accepted", id));
  }

  /**
   * Restart an execution from one node.
   *
   * @param executionId the execution
   * @param fromNodeId the node to restart from
   * @return the new execution ID
   */
  @PostMapping({
    "/executions/{executionId}/restart/{fromNodeId}",
    "/workflow/executions/{executionId}/restart/{fromNodeId}"
  })
  @Operation(
      summary = "Restart a workflow execution from a node",
      description =
          "Restarts from a node, replaying the last checkpoints of its parents. Legacy path:"
              + " /api/workflow/executions/{executionId}/restart/{fromNodeId} (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> restartFromNode(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = "Node ID to restart from") @PathVariable final String fromNodeId) {
    return controlBus
        .restartFromNode(executionId, fromNodeId)
        .map(id -> started("Workflow restart from node accepted", id));
  }

  /**
   * Get an execution's progress.
   *
   * @param sessionId the owning session (legacy path only)
   * @param session the owning session as a query parameter: reads a run from the session's history
   *     once the execution index has dropped it, and checks that the run belongs to the session
   * @param executionId the execution
   * @return the progress snapshot
   */
  @GetMapping({"/executions/{executionId}", "/workflow/{sessionId}/status/{executionId}"})
  @Operation(
      summary = "Get workflow execution status",
      description =
          "Pass sessionId to read a run older than the execution index from its session's"
              + " history. Legacy path: /api/workflow/{sessionId}/status/{executionId}"
              + " (deprecated)")
  public Mono<ApiResponse<WorkflowProgress>> getWorkflowStatus(
      @Parameter(description = "Session ID") @PathVariable(required = false) final String sessionId,
      @Parameter(description = "Session ID, to read from the session's history")
          @RequestParam(name = "sessionId", required = false)
          final String session,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return access
        .require(sessionId == null ? session : sessionId, executionId)
        .map(
            progress ->
                ApiResponse.success(200, "Workflow status retrieved successfully", progress));
  }

  /**
   * Stream an execution's progress.
   *
   * @param sessionId the owning session (legacy path only)
   * @param executionId the execution
   * @param includeHistory whether to replay recent status updates first
   * @param lastEventId ignored: the replay window moves with time (or is off), so event IDs of an
   *     earlier connection do not match this one, and skipping by them would drop live updates. A
   *     reconnecting client gets the recent updates again, which are states and safe to repeat.
   * @return progress events with IDs and a keep-alive comment every 15 seconds
   */
  @GetMapping(
      value = {
        "/executions/{executionId}/status/stream",
        "/workflow/{sessionId}/status/{executionId}/stream"
      },
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream workflow execution status",
      description =
          "Streams progress via SSE. Legacy path:"
              + " /api/workflow/{sessionId}/status/{executionId}/stream (deprecated)")
  public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
      @Parameter(description = "Session ID") @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = "Include historical status updates (last N minutes)")
          @RequestParam(defaultValue = "true")
          final boolean includeHistory,
      @RequestHeader(value = "Last-Event-ID", required = false) final String lastEventId) {
    return access
        .require(sessionId, executionId)
        .flatMapMany(
            _ ->
                SseStreams.events(
                    controlBus.watchExecution(executionId, includeHistory),
                    null,
                    SseStreams.HEARTBEAT));
  }

  private static ApiResponse<WorkflowStartResponse> started(
      final String message, final String executionId) {
    return ApiResponse.success(200, message, new WorkflowStartResponse(executionId));
  }
}
