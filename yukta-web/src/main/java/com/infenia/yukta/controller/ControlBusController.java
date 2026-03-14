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

import com.infenia.yukta.model.ApiResponse;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.ControlBusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for the Control Bus Management Console.
 *
 * <p>Provides endpoints for monitoring and managing the system's control traffic.
 */
@RestController
@RequestMapping("/api/control")
@RequiredArgsConstructor
@Tag(name = "Control Bus API", description = "Endpoints for system management and monitoring")
public class ControlBusController {
  private final ControlBusService controlBusService;

  /**
   * Get all active nodes that have emitted heartbeats.
   *
   * @return list of active node IDs
   */
  @GetMapping("/nodes")
  @Operation(
      summary = "Get active nodes",
      description = "Lists all nodes currently registered on the Control Bus")
  public Mono<ApiResponse<List<String>>> getActiveNodes() {
    return Mono.fromCallable(controlBusService::getActiveNodes)
        .map(nodes -> ApiResponse.success(200, "Active nodes retrieved", nodes));
  }

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message
   */
  @GetMapping("/nodes/{nodeId}/heartbeat")
  @Operation(
      summary = "Get node heartbeat",
      description = "Retrieves the most recent heartbeat for a specific node")
  public Mono<ApiResponse<Message<?>>> getLastHeartbeat(@PathVariable final String nodeId) {
    return Mono.fromCallable(() -> controlBusService.getLastHeartbeat(nodeId))
        .map(hb -> ApiResponse.success(200, "Node heartbeat retrieved", hb));
  }

  /**
   * Send a command to a specific node.
   *
   * @param nodeId the target node identifier
   * @param payload the command payload
   * @return a Mono of the response API response
   */
  @PostMapping("/nodes/{nodeId}/command")
  @Operation(
      summary = "Send command to node",
      description = "Sends an administrative command to a specific node")
  public Mono<ApiResponse<Message<?>>> sendCommand(
      @PathVariable final String nodeId, @RequestBody final Map<String, Object> payload) {
    final Message<?> command =
        DefaultMessage.create(null, payload).withControl(true).withSourceNodeId("CONSOLE");
    return controlBusService
        .sendCommand(nodeId, command)
        .map(resp -> ApiResponse.success(200, "Command processed", resp));
  }

  /**
   * Stream all control signals via SSE.
   *
   * @return a flux of control signals
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream control signals",
      description = "Streams all system-wide control signals via SSE")
  public Flux<ServerSentEvent<Message<?>>> streamControlSignals() {
    return controlBusService
        .getControlStream()
        .map(msg -> ServerSentEvent.<Message<?>>builder().data(msg).build());
  }
}
