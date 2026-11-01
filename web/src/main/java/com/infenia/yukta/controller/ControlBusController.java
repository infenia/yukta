// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.NodeCommandRequest;
import com.infenia.yukta.dto.response.NodeCommandResponse;
import com.infenia.yukta.exception.ResourceNotFoundException;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.control.NodeHeartbeat;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for control bus operations and execution observability. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(
    name = "Control Bus & Observability API",
    description =
        "Endpoints for control bus operations, node management, and execution observability")
public class ControlBusController {
  /** The control bus gateway for managing workflow operations and observability. */
  private final ControlBusGateway controlBus;

  /**
   * Get all active nodes in a specific workflow that have emitted heartbeats.
   *
   * @param workflowId the workflow identifier
   * @param sessionId the session that owns the workflow
   * @return list of active node IDs in the workflow
   */
  @GetMapping("/control/workflows/{workflowId}/nodes")
  @Operation(
      summary = "Get active nodes in workflow",
      description =
          "Lists all nodes in a specific workflow currently registered on the Control Bus")
  public Mono<ApiResponse<List<String>>> getActiveNodes(
      @PathVariable final String workflowId, @RequestParam final String sessionId) {
    return Mono.fromCallable(() -> controlBus.getActiveNodes(sessionId, workflowId))
        .map(nodes -> ApiResponse.success(200, "Active nodes retrieved", nodes));
  }

  /**
   * Get the last heartbeat for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param sessionId the session that owns the workflow
   * @param executionId the run; may be left out when the session has one live run of the workflow
   * @return the last heartbeat message
   */
  @GetMapping("/control/workflows/{workflowId}/nodes/{nodeId}/heartbeat")
  @Operation(
      summary = "Get node heartbeat in workflow",
      description = "Retrieves the most recent heartbeat for a specific node in a workflow")
  public Mono<ApiResponse<Message<?>>> getLastHeartbeat(
      @PathVariable final String workflowId,
      @PathVariable final String nodeId,
      @RequestParam final String sessionId,
      @RequestParam(required = false) final String executionId) {
    return Mono.fromCallable(
            () -> controlBus.getLastHeartbeat(sessionId, workflowId, executionId, nodeId))
        .switchIfEmpty(
            Mono.error(() -> new ResourceNotFoundException("Heartbeat", workflowId + "/" + nodeId)))
        .map(hb -> ApiResponse.success(200, "Node heartbeat retrieved", hb));
  }

  /**
   * Get the last heartbeat of every node of every run.
   *
   * @return one heartbeat per node and run
   */
  @GetMapping("/control/heartbeats")
  @Operation(
      summary = "Get every node's heartbeat",
      description =
          "Lists the last heartbeat of every node of every run on the Control Bus, in one call")
  public Mono<ApiResponse<List<NodeHeartbeat>>> getHeartbeats() {
    return Mono.fromCallable(controlBus::getHeartbeats)
        .map(beats -> ApiResponse.success(200, "Heartbeats retrieved", beats));
  }

  /**
   * Send a command to a specific node in a workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the target node identifier
   * @param sessionId the session that owns the workflow
   * @param executionId the run; may be left out when the session has one live run of the workflow
   * @param request the command payload
   * @return a Mono of the response API response
   */
  @PostMapping("/control/workflows/{workflowId}/nodes/{nodeId}/command")
  @Operation(
      summary = "Send command to node in workflow",
      description = "Sends an administrative command to a specific node in a workflow")
  public Mono<ApiResponse<NodeCommandResponse>> sendCommand(
      @PathVariable final String workflowId,
      @PathVariable final String nodeId,
      @RequestParam final String sessionId,
      @RequestParam(required = false) final String executionId,
      @Valid @RequestBody final NodeCommandRequest request) {
    final Message<?> command =
        DefaultMessage.create(null, request.payload())
            .withControl(true)
            .withSourceNodeId("CONSOLE")
            .withWorkflowId(workflowId);
    return controlBus
        .sendCommand(sessionId, workflowId, executionId, nodeId, command)
        .map(
            resp ->
                ApiResponse.success(
                    200, "Command processed", new NodeCommandResponse(resp.payload())));
  }

  /**
   * Get all active nodes across all workflows that have emitted heartbeats.
   *
   * @return list of all active node IDs
   */
  @GetMapping("/control/nodes")
  @Operation(
      summary = "Get active nodes (global)",
      description = "Lists all nodes currently registered on the Control Bus across all workflows")
  public Mono<ApiResponse<List<String>>> getAllActiveNodes() {
    return Mono.fromCallable(controlBus::getActiveNodes)
        .map(nodes -> ApiResponse.success(200, "Active nodes retrieved", nodes));
  }
}
