// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowGraph;
import com.infenia.yukta.dto.response.WorkflowGraphNode;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Workflow DAG graph with plugin metadata, and the per-workflow execution history. */
@RestController
@RequestMapping("/api/sessions/{sessionId}/workflows/{workflowId}")
@RequiredArgsConstructor
@Tag(
    name = "Workflow Details API",
    description = "Workflow DAG graph with plugin metadata and per-workflow execution history")
public class WorkflowDetailsController {

  /** Session ID parameter description. */
  /** The plugin that runs another workflow of the session. */
  private static final String SUB_WORKFLOW = "SUB_WORKFLOW";

  private static final String SESSION_ID = "The unique identifier of the session";

  /** Workflow ID parameter description. */
  private static final String WORKFLOW_ID = "The unique identifier of the workflow";

  /** The session service. */
  private final SessionService sessionService;

  /** The workflow graph service. */
  private final WorkflowGraphService workflowGraphService;

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The workflow mapper. */
  private final WorkflowMapper workflowMapper;

  /**
   * Get the workflow DAG enriched with plugin metadata.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return nodes, edges and topological order
   */
  @GetMapping("/graph")
  @Operation(
      summary = "Get the workflow DAG enriched with plugin metadata",
      description =
          "Nodes and edges enriched with plugin category, description, UI design and"
              + " config-aware output ports, plus the topological execution order")
  public Mono<ApiResponse<WorkflowGraph>> getWorkflowGraph(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId) {
    return definition(sessionId, workflowId)
        .map(def -> ApiResponse.success(200, "Workflow graph retrieved", buildGraph(def)));
  }

  /**
   * List the executions of one workflow, newest first.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param limit page size (default 500, at most 1000)
   * @param cursor execution ID of the last item of the previous page
   * @return one page of execution summaries
   */
  @GetMapping("/executions")
  @Operation(
      summary = "Get the execution history for a workflow",
      description =
          "Execution summaries of a single workflow, most recent first. An empty list means the"
              + " workflow has never been executed. A further page is announced in the"
              + " X-Next-Cursor header.")
  public Mono<ResponseEntity<ApiResponse<List<WorkflowExecutionSummary>>>> getWorkflowExecutions(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId,
      @RequestParam(required = false) final Integer limit,
      @RequestParam(required = false) final String cursor) {
    return definition(sessionId, workflowId)
        .flatMap(def -> controlBus.getHistory(sessionId, workflowId))
        .map(
            executions ->
                Pagination.page(
                    executions,
                    limit,
                    cursor,
                    WorkflowExecutionSummary::executionId,
                    "Workflow executions retrieved"));
  }

  private Mono<WorkflowDefinition> definition(final String sessionId, final String workflowId) {
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new NotFoundException(
                        "Workflow not found: '"
                            + workflowId
                            + "' in session: '"
                            + sessionId
                            + "'")));
  }

  /** The workflow a SUB_WORKFLOW node runs, so the UI can open it; null for any other node. */
  private static @Nullable String subWorkflowId(final WorkflowDefinition.Node node) {
    return SUB_WORKFLOW.equals(node.type()) && node.config().get("workflowId") instanceof String id
        ? id
        : null;
  }

  private WorkflowGraph buildGraph(final WorkflowDefinition definition) {
    final List<WorkflowGraphNode> nodes =
        definition.nodes().stream()
            .map(
                node -> {
                  final var enriched = workflowGraphService.enrichNode(node);
                  return new WorkflowGraphNode(
                      enriched.nodeId(),
                      enriched.type(),
                      enriched.category(),
                      enriched.description(),
                      enriched.uiDesign(),
                      enriched.outputPorts(),
                      node.group(),
                      subWorkflowId(node));
                })
            .toList();
    return new WorkflowGraph(
        definition.workflowId(),
        definition.description(),
        nodes,
        workflowMapper.toGraphEdges(definition.edges()),
        workflowGraphService.computeTopologicalOrder(definition));
  }
}
