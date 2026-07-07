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

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for control bus operations and execution observability. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
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
   * @return list of active node IDs in the workflow
   */
  @GetMapping("/control/workflows/{workflowId}/nodes")
  @Operation(
      summary = "Get active nodes in workflow",
      description =
          "Lists all nodes in a specific workflow currently registered on the Control Bus")
  public Mono<ApiResponse<List<String>>> getActiveNodes(@PathVariable final String workflowId) {
    log.atInfo().log("getActiveNodes reached: workflowId={}", workflowId);
    return Mono.fromCallable(() -> controlBus.getActiveNodes(workflowId))
        .map(nodes -> ApiResponse.success(200, "Active nodes retrieved", nodes));
  }

  /**
   * Get the last heartbeat for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last heartbeat message
   */
  @GetMapping("/control/workflows/{workflowId}/nodes/{nodeId}/heartbeat")
  @Operation(
      summary = "Get node heartbeat in workflow",
      description = "Retrieves the most recent heartbeat for a specific node in a workflow")
  public Mono<ApiResponse<Message<?>>> getLastHeartbeat(
      @PathVariable final String workflowId, @PathVariable final String nodeId) {
    log.atInfo().log("getLastHeartbeat reached: workflowId={}, nodeId={}", workflowId, nodeId);
    return Mono.fromCallable(() -> controlBus.getLastHeartbeat(workflowId, nodeId))
        .map(hb -> ApiResponse.success(200, "Node heartbeat retrieved", hb));
  }

  /**
   * Send a command to a specific node in a workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the target node identifier
   * @param payload the command payload
   * @return a Mono of the response API response
   */
  @PostMapping("/control/workflows/{workflowId}/nodes/{nodeId}/command")
  @Operation(
      summary = "Send command to node in workflow",
      description = "Sends an administrative command to a specific node in a workflow")
  public Mono<ApiResponse<Message<?>>> sendCommand(
      @PathVariable final String workflowId,
      @PathVariable final String nodeId,
      @RequestBody final Map<String, Object> payload) {
    log.atInfo().log("sendCommand reached: workflowId={}, nodeId={}", workflowId, nodeId);
    final Message<?> command =
        DefaultMessage.create(null, payload)
            .withControl(true)
            .withSourceNodeId("CONSOLE")
            .withWorkflowId(workflowId);
    return controlBus
        .sendCommand(workflowId, nodeId, command)
        .map(resp -> ApiResponse.success(200, "Command processed", resp));
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
    log.atInfo().log("getAllActiveNodes reached");
    return Mono.fromCallable(controlBus::getActiveNodes)
        .map(nodes -> ApiResponse.success(200, "Active nodes retrieved", nodes));
  }
}
