// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.NodeCommandRequest;
import com.infenia.yukta.dto.response.NodeCommandResponse;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
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
}
