// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowExecutions;
import com.infenia.yukta.dto.response.WorkflowGraph;
import com.infenia.yukta.dto.response.WorkflowGraphNode;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controller for workflow detail views.
 *
 * <p>Serves the workflow DAG enriched with plugin metadata for UI canvas rendering, plus the
 * per-workflow execution history.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/workflows/{workflowId}")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Workflow Details API",
    description = "Workflow DAG graph with plugin metadata and per-workflow execution history")
public class WorkflowDetailsController {

  /** Content type constant for JSON responses. */
  private static final String APPLICATION_JSON = "application/json";

  /** HTTP 200 response code constant for Swagger documentation. */
  private static final String HTTP_200 = "200";

  /** HTTP 500 response code constant for Swagger documentation. */
  private static final String HTTP_500 = "500";

  /** Internal server error message constant for Swagger documentation. */
  private static final String INTERNAL_SERVER_ERROR = "Internal server error";

  /** Workflow not found error message constant. */
  private static final String WORKFLOW_NOT_FOUND = "Workflow not found";

  /** Log key for the session identifier. */
  private static final String SESSION_ID = "sessionId";

  /** Log key for the workflow identifier. */
  private static final String WORKFLOW_ID = "workflowId";

  /** The service for managing sessions. */
  private final SessionService sessionService;

  /** The core service for enriching workflow graphs. */
  private final WorkflowGraphService workflowGraphService;

  /** The control bus gateway for execution history. */
  private final ControlBusGateway controlBus;

  /** The mapper for workflow graph DTOs. */
  private final WorkflowMapper workflowMapper;

  /**
   * Get the workflow DAG enriched with plugin metadata.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param exchange implicit Spring parameter used to extract request path for error responses
   * @return the enriched workflow graph
   */
  @GetMapping("/graph")
  @Operation(
      summary = "Get the workflow DAG enriched with plugin metadata",
      description =
          "Retrieves the workflow nodes and edges enriched with plugin category, description,"
              + " UI design and config-aware output ports, plus the topological execution order."
              + " Response is non-blocking and returned asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow graph retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = WORKFLOW_NOT_FOUND,
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<WorkflowGraph>>> getWorkflowGraph(
      @Parameter(description = "The unique identifier of the session") @PathVariable
          final String sessionId,
      @Parameter(description = "The unique identifier of the workflow") @PathVariable
          final String workflowId,
      final ServerWebExchange exchange) {
    log.atInfo()
        .addKeyValue(SESSION_ID, sessionId)
        .addKeyValue(WORKFLOW_ID, workflowId)
        .log("getWorkflowGraph requested");
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .map(
            definition ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(), "Workflow graph retrieved", buildGraph(definition))))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn()
                      .addKeyValue(SESSION_ID, sessionId)
                      .addKeyValue(WORKFLOW_ID, workflowId)
                      .log("getWorkflowGraph workflow not found");
                  return buildNotFoundResponse(
                      "Workflow not found: '" + workflowId + "' in session: '" + sessionId + "'",
                      exchange);
                }))
        .doOnError(
            error ->
                log.atError()
                    .addKeyValue(SESSION_ID, sessionId)
                    .addKeyValue(WORKFLOW_ID, workflowId)
                    .log("getWorkflowGraph error occurred: error={}", error.getMessage()));
  }

  /**
   * Get the execution history for a single workflow, most recent first.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param exchange implicit Spring parameter used to extract request path for error responses
   * @return the per-workflow execution history
   */
  @GetMapping("/executions")
  @Operation(
      summary = "Get the execution history for a workflow",
      description =
          "Retrieves the execution summaries of a single workflow, most recent first. An empty"
              + " list means the workflow has never been executed. Response is non-blocking and"
              + " returned asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow executions retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = WORKFLOW_NOT_FOUND,
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<WorkflowExecutions>>> getWorkflowExecutions(
      @Parameter(description = "The unique identifier of the session") @PathVariable
          final String sessionId,
      @Parameter(description = "The unique identifier of the workflow") @PathVariable
          final String workflowId,
      final ServerWebExchange exchange) {
    log.atInfo()
        .addKeyValue(SESSION_ID, sessionId)
        .addKeyValue(WORKFLOW_ID, workflowId)
        .log("getWorkflowExecutions requested");
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .flatMap(
            definition ->
                controlBus
                    .getHistory(sessionId, workflowId)
                    .map(
                        executions ->
                            ResponseEntity.ok(
                                ApiResponse.success(
                                    HttpStatus.OK.value(),
                                    "Workflow executions retrieved",
                                    new WorkflowExecutions(executions)))))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn()
                      .addKeyValue(SESSION_ID, sessionId)
                      .addKeyValue(WORKFLOW_ID, workflowId)
                      .log("getWorkflowExecutions workflow not found");
                  return buildNotFoundResponse(
                      "Workflow not found: '" + workflowId + "' in session: '" + sessionId + "'",
                      exchange);
                }))
        .doOnError(
            error ->
                log.atError()
                    .addKeyValue(SESSION_ID, sessionId)
                    .addKeyValue(WORKFLOW_ID, workflowId)
                    .log("getWorkflowExecutions error occurred: error={}", error.getMessage()));
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
                      enriched.outputPorts());
                })
            .toList();
    return new WorkflowGraph(
        definition.workflowId(),
        definition.description(),
        nodes,
        workflowMapper.toGraphEdges(definition.edges()),
        workflowGraphService.computeTopologicalOrder(definition));
  }

  private <T> ResponseEntity<ApiResponse<T>> buildNotFoundResponse(
      final String errorMessage, final ServerWebExchange exchange) {
    @SuppressWarnings("PMD.LawOfDemeter")
    final var request = exchange.getRequest();
    final String path = request.getPath().value();
    final List<ApiResponse.FieldError> errors =
        List.of(new ApiResponse.FieldError(WORKFLOW_ID, errorMessage));
    @SuppressWarnings({"unchecked", "rawtypes"})
    final ResponseEntity entity =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(
                ApiResponse.error(
                    HttpStatus.NOT_FOUND.value(), "Not Found", WORKFLOW_NOT_FOUND, path, errors));
    @SuppressWarnings("unchecked")
    final ResponseEntity<ApiResponse<T>> result = (ResponseEntity<ApiResponse<T>>) entity;
    return result;
  }
}
