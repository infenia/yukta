// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.control.PendingApproval;
import com.infenia.yukta.model.control.SchedulerLimits;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@SuppressWarnings("PMD.TooManyMethods")
public class ExecutionControlController {

  /** Session ID parameter description. */
  private static final String SESSION_ID = "Session ID";

  /** Node ID parameter description. */
  private static final String NODE_ID = "Node ID";

  /** Execution ID parameter description. */
  private static final String EXECUTION_ID = "Execution ID";

  /** Tree parameter description. */
  private static final String TREE =
      "Also every unfinished run the execution started as a sub-workflow, at any depth";

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The access. */
  private final ExecutionAccess access;

  /** Continues runs that a server restart aborted. */
  private final ExecutionResumer resumer;

  /** Applies a command to a whole tree of runs. */
  private final TreeControl treeControl;

  /** Admits the runs; its drain switch backs {@code /admin/drain}. */
  private final ExecutionScheduler scheduler;

  /** Pause a workflow execution. */
  @PostMapping({"/executions/{executionId}/pause", "/workflow/{sessionId}/{executionId}/pause"})
  @Operation(
      summary = "Pause a workflow execution",
      description =
          "Pauses a specific workflow execution via global backpressure; with tree=true also every"
              + " run it started as a sub-workflow. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/pause (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> pauseWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = TREE) @RequestParam(defaultValue = "false") final boolean tree) {
    return signal(
        sessionId,
        executionId,
        "Workflow pause signal accepted",
        () -> treeControl.apply(executionId, tree, controlBus::pauseWorkflow));
  }

  /** Resume a workflow execution. */
  @PostMapping({"/executions/{executionId}/resume", "/workflow/{sessionId}/{executionId}/resume"})
  @Operation(
      summary = "Resume a workflow execution",
      description =
          "Resumes a specific paused workflow execution; with tree=true also every run it started"
              + " as a sub-workflow. Legacy path:"
              + " /api/workflow/{sessionId}/{executionId}/resume (deprecated)")
  public Mono<ApiResponse<WorkflowStartResponse>> resumeWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = TREE) @RequestParam(defaultValue = "false") final boolean tree) {
    return signal(
        sessionId,
        executionId,
        "Workflow resume signal accepted",
        () -> treeControl.apply(executionId, tree, controlBus::resumeWorkflow));
  }

  /** Continue a run that a server restart aborted, as a new run. */
  @PostMapping({"/executions/{executionId}/recover", "/workflow/{sessionId}/{executionId}/recover"})
  @Operation(
      summary = "Recover an aborted workflow execution",
      description =
          "Continues a run that a server restart aborted as a new run: nodes that finished are"
              + " replayed from their recorded output, the rest run again. Needs the file"
              + " execution log and checkpoint directories. Answers 409 if the run was not"
              + " aborted, was already resumed or its workflow changed.")
  public Mono<ApiResponse<WorkflowStartResponse>> recoverExecution(
      @Parameter(description = SESSION_ID) @PathVariable(required = false) final String sessionId,
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId) {
    return access
        .require(sessionId, executionId)
        .then(Mono.defer(() -> resumer.resume(executionId)))
        .map(
            newId ->
                ApiResponse.success(
                    200, "Execution recovered as a new run", new WorkflowStartResponse(newId)));
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

  /** Retry a failed node. */
  @PostMapping("/executions/{executionId}/nodes/{nodeId}/retry")
  @Operation(
      summary = "Retry a failed node",
      description = "Restarts the execution from a node that failed. Returns the new execution.")
  public Mono<ApiResponse<WorkflowStartResponse>> retryNode(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return access
        .require(null, executionId)
        .then(Mono.defer(() -> controlBus.retryNode(executionId, nodeId)))
        .map(
            newId ->
                ApiResponse.success(200, "Node retry accepted", new WorkflowStartResponse(newId)));
  }

  /** Set a breakpoint on a node. */
  @PostMapping("/executions/{executionId}/nodes/{nodeId}/breakpoint")
  @Operation(
      summary = "Set a breakpoint on a node",
      description =
          "Pauses the node when the given payload field equals the given value, for example"
              + " field=payload.status&equals=error")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<ApiResponse<WorkflowStartResponse>> addBreakpoint(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId,
      @Parameter(description = "Field to compare, for example payload.status") @RequestParam
          final String field,
      @Parameter(description = "Value that triggers the breakpoint") @RequestParam("equals")
          final String equalsValue) {
    return signal(
        null,
        executionId,
        "Breakpoint accepted",
        () -> controlBus.addBreakpoint(executionId, nodeId, field, equalsValue));
  }

  /** Send a signal (an approval or decision) to a node that waits for one. */
  @PostMapping("/executions/{executionId}/nodes/{nodeId}/signal")
  @Operation(
      summary = "Send a signal to a waiting node",
      description =
          "Releases or rejects a node that waits for the named signal, for example a"
              + " WAIT_FOR_SIGNAL approval step. Answers 409 if the node is not waiting for it.")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<ApiResponse<WorkflowStartResponse>> signalNode(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId,
      @Parameter(description = "Name of the signal the node waits for") @RequestParam
          final String name,
      @Parameter(description = "Decision, for example approve or reject") @RequestParam
          final String decision,
      @Parameter(description = "Optional text that goes with the decision")
          @RequestParam(required = false)
          final String data) {
    return signal(
        null,
        executionId,
        "Signal accepted",
        () -> controlBus.signalNode(executionId, nodeId, name, decision, data));
  }

  /** List the nodes of a session that wait for a signal. */
  @GetMapping("/sessions/{sessionId}/approvals")
  @Operation(
      summary = "List pending approvals",
      description =
          "Lists the nodes of the session that wait for a signal now (for example WAIT_FOR_SIGNAL"
              + " approval steps), oldest first. Answer one with the signal endpoint of its"
              + " execution.")
  public Mono<ApiResponse<List<PendingApproval>>> pendingApprovals(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId) {
    return controlBus
        .pendingApprovals(sessionId)
        .collectList()
        .map(list -> ApiResponse.success(200, "Pending approvals retrieved", list));
  }

  /** Clear a node's breakpoint. */
  @DeleteMapping("/executions/{executionId}/nodes/{nodeId}/breakpoint")
  @Operation(summary = "Clear a breakpoint on a node")
  public Mono<ApiResponse<WorkflowStartResponse>> clearBreakpoint(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = NODE_ID) @PathVariable final String nodeId) {
    return signal(
        null,
        executionId,
        "Breakpoint cleared",
        () -> controlBus.clearBreakpoint(executionId, nodeId));
  }

  /** Change the priority of an execution that waits for a slot. */
  @PostMapping("/executions/{executionId}/priority")
  @Operation(
      summary = "Change the priority of a queued execution",
      description = "Rejected unless the execution is waiting for a slot")
  public Mono<ApiResponse<WorkflowStartResponse>> changePriority(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = "New priority; higher runs first") @RequestParam
          final int priority) {
    return signal(
        null,
        executionId,
        "Priority change accepted",
        () -> controlBus.changePriority(executionId, priority));
  }

  /** The execution limits in force and the slots in use. */
  @GetMapping("/scheduler/limits")
  @Operation(
      summary = "Read the execution limits",
      description =
          "The limits set at startup or by the last POST (0 means unlimited), with how many"
              + " executions run and wait now")
  public Mono<ApiResponse<SchedulerLimits>> getLimits() {
    return controlBus
        .limits()
        .map(limits -> ApiResponse.success(200, "Execution limits retrieved", limits));
  }

  /** Change how many executions may run at once. */
  @PostMapping("/scheduler/limits")
  @Operation(summary = "Change the execution limits")
  public Mono<ApiResponse<Map<String, Integer>>> changeLimits(
      @Parameter(description = "Most executions running at once") @RequestParam
          final int maxConcurrent,
      @Parameter(description = "Most executions of one workflow running at once") @RequestParam
          final int maxPerWorkflow) {
    // 0 means unlimited, so a negative value must not slip through as 0.
    if (maxConcurrent < 0 || maxPerWorkflow < 0) {
      throw new IllegalArgumentException("Limits must be 0 (unlimited) or greater");
    }
    return controlBus
        .changeLimits(maxConcurrent, maxPerWorkflow)
        .thenReturn(
            ApiResponse.success(
                200,
                "Limits changed",
                Map.of("maxConcurrent", maxConcurrent, "maxPerWorkflow", maxPerWorkflow)));
  }

  /** Refuse new starts so the server can be swapped; the runs already going are not touched. */
  @PostMapping("/admin/drain")
  @Operation(
      summary = "Drain the server",
      description =
          "Refuses new starts (HTTP 429) while running ones carry on. Poll GET until idle is true,"
              + " then swap the server; DELETE admits starts again. Admin keys only.")
  public Mono<ApiResponse<Map<String, Object>>> drain() {
    scheduler.setDraining(true);
    return drainStatus();
  }

  /** Admit new starts again after a drain. */
  @DeleteMapping("/admin/drain")
  @Operation(summary = "Stop draining the server")
  public Mono<ApiResponse<Map<String, Object>>> undrain() {
    scheduler.setDraining(false);
    return drainStatus();
  }

  /** Whether the server drains and whether anything is still running or waiting. */
  @GetMapping("/admin/drain")
  @Operation(summary = "Drain state", description = "idle is true once nothing runs or waits")
  public Mono<ApiResponse<Map<String, Object>>> drainStatus() {
    final int running = scheduler.running();
    final int queued = scheduler.queued();
    return Mono.just(
        ApiResponse.success(
            200,
            "Drain state",
            Map.of(
                "draining",
                scheduler.isDraining(),
                "running",
                running,
                "queued",
                queued,
                "idle",
                running + queued == 0)));
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
