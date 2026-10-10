// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Pause, resume and per-node control of a running execution. Each operation lives at {@code
 * /api/executions/{executionId}/…} and, for existing clients, at its legacy {@code /api/workflow/…}
 * path, which also verifies that the execution belongs to the session in the path.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Execution Control API", description = "Pause, resume, step and stop nodes")
public class ExecutionControlController {

  /** Session ID parameter description. */
  private static final String SESSION_ID = "Session ID";

  /** Node ID parameter description. */
  private static final String NODE_ID = "Node ID";

  /** Execution ID parameter description. */
  private static final String EXECUTION_ID = "Execution ID";

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The access. */
  private final ExecutionAccess access;

  /** Pause a workflow execution. */
  @PostMapping({"/executions/{executionId}/pause", "/workflow/{sessionId}/{executionId}/pause"})
  @Operation(
      summary = "Pause a workflow execution",
      description =
          "Pauses a specific workflow execution via global backpressure. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/pause (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> pauseWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return signal(
        sessionId,
        executionId,
        "Workflow pause signal accepted",
        () -> controlBus.pauseWorkflow(executionId));
  }

  /** Resume a workflow execution. */
  @PostMapping({"/executions/{executionId}/resume", "/workflow/{sessionId}/{executionId}/resume"})
  @Operation(
      summary = "Resume a workflow execution",
      description =
          "Resumes a specific paused workflow execution. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/resume (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> resumeWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return signal(
        sessionId,
        executionId,
        "Workflow resume signal accepted",
        () -> controlBus.resumeWorkflow(executionId));
  }

  /** Pause a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/pause",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/pause"
  })
  @Operation(
      summary = "Pause a node",
      description =
          "Pauses a single node via backpressure. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/pause (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> pauseNode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        sessionId,
        executionId,
        "Node pause signal accepted",
        () -> controlBus.pauseNode(executionId, nodeId));
  }

  /** Resume a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/resume",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/resume"
  })
  @Operation(
      summary = "Resume a node",
      description =
          "Resumes a single paused node. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/resume (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> resumeNode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        sessionId,
        executionId,
        "Node resume signal accepted",
        () -> controlBus.resumeNode(executionId, nodeId));
  }

  /** Stop a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/stop",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/stop"
  })
  @Operation(
      summary = "Stop a node",
      description =
          "Stops a single node, immediately or after draining inflight work. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/stop (deprecated)")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<ApiResponse<WorkflowStartResponse>> stopNode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId,
      @Parameter(description = "True for hard stop, false for drain & stop")
          @RequestParam(defaultValue = "false")
          final boolean immediate,
      @Parameter(description = "Human-readable reason for the stop")
          @RequestParam(defaultValue = "Stopped via REST API")
          final String reason) {
    return signal(
        sessionId,
        executionId,
        "Node stop signal accepted",
        () -> controlBus.stopNode(executionId, nodeId, immediate, reason));
  }

  /** Skip or unskip a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/skip",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/skip"
  })
  @Operation(
      summary = "Skip or unskip a node",
      description =
          "Marks a node as skipped (passthrough) or not. Core currently rejects this for running"
              + " executions.. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/skip (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> skipNode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId,
      @Parameter(description = "True to skip, false to unskip") @RequestParam(defaultValue = "true")
          final boolean skip) {
    return signal(
        sessionId,
        executionId,
        "Node skip signal accepted",
        () -> controlBus.skipNode(executionId, nodeId, skip));
  }

  /** Enable step mode on a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/step/enable",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/step/enable"
  })
  @Operation(
      summary = "Enable step mode on a node",
      description =
          "Enables step-through debug mode on a node. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/step/enable (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> enableStepMode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        sessionId,
        executionId,
        "Step mode enable signal accepted",
        () -> controlBus.enableStepMode(executionId, nodeId));
  }

  /** Disable step mode on a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/step/disable",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/step/disable"
  })
  @Operation(
      summary = "Disable step mode on a node",
      description =
          "Disables step-through debug mode on a node. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/step/disable (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> disableStepMode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        sessionId,
        executionId,
        "Step mode disable signal accepted",
        () -> controlBus.disableStepMode(executionId, nodeId));
  }

  /** Step a node. */
  @PostMapping({
    "/executions/{executionId}/nodes/{nodeId}/step",
    "/workflow/{sessionId}/{executionId}/node/{nodeId}/step"
  })
  @Operation(
      summary = "Step a node",
      description =
          "Lets exactly one element through a node in step-through mode. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/node/{nodeId}/step (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> stepNode(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        sessionId,
        executionId,
        "Node step signal accepted",
        () -> controlBus.stepNode(executionId, nodeId));
  }

  private Mono<ApiResponse<WorkflowStartResponse>> signal(
      final String sessionId,
      final String executionId,
      final String message,
      final Supplier<Mono<Void>> action) {
    return access
        .require(sessionId, executionId)
        .then(Mono.defer(action))
        .thenReturn(ApiResponse.success(200, message, new WorkflowStartResponse(executionId)));
  }
}
