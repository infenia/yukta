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
package com.infenia.yukta.cli;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.model.api.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** HTTP client for communicating with the Yukta daemon. */
@Component
@RequiredArgsConstructor
@Slf4j
public class YuktaDaemonClient {

  /** WebClient for communicating with the daemon. */
  private final WebClient daemonWebClient;

  /** Daemon configuration properties. */
  private final DaemonProperties props;

  /**
   * Applies session configuration to the daemon.
   *
   * @param request the session configuration request
   */
  public void applySession(ConfigRequest request) {
    ApiResponse<Void> response =
        daemonWebClient
            .post()
            .uri("/api/sessions")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Void>>() {})
            .block();

    if (response != null) {
      log.info("Session applied: {}", request.sessionId());
    }
  }

  /**
   * Triggers a workflow execution.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @return the execution ID
   */
  public String triggerWorkflow(String sessionId, String workflowId) {
    Map<String, Object> request = Map.of("sessionId", sessionId, "workflowId", workflowId);

    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .post()
            .uri("/api/workflow/trigger")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    if (response != null && response.data() != null) {
      return (String) response.data().get("executionId");
    }
    throw new RuntimeException("Failed to trigger workflow");
  }

  /**
   * Gets active nodes for a workflow.
   *
   * @param workflowId the workflow ID
   * @return list of active node IDs
   */
  public List<String> getActiveNodes(String workflowId) {
    ApiResponse<List<String>> response =
        daemonWebClient
            .get()
            .uri("/api/control/workflows/{workflowId}/nodes", workflowId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<List<String>>>() {})
            .block();

    return response != null ? response.data() : List.of();
  }

  /**
   * Gets all active nodes across all workflows.
   *
   * @return list of all active node IDs
   */
  public List<String> getAllActiveNodes() {
    ApiResponse<List<String>> response =
        daemonWebClient
            .get()
            .uri("/api/control/nodes")
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<List<String>>>() {})
            .block();

    return response != null ? response.data() : List.of();
  }

  /**
   * Gets the last heartbeat from a node.
   *
   * @param workflowId the workflow ID
   * @param nodeId the node ID
   * @return heartbeat data map
   */
  public Map<String, Object> getLastHeartbeat(String workflowId, String nodeId) {
    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .get()
            .uri("/api/control/workflows/{workflowId}/nodes/{nodeId}/heartbeat", workflowId, nodeId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    return response != null ? response.data() : Map.of();
  }

  /**
   * Sends a command to a node.
   *
   * @param workflowId the workflow ID
   * @param nodeId the node ID
   * @param payload the command payload
   * @return response data map
   */
  public Map<String, Object> sendCommand(
      String workflowId, String nodeId, Map<String, Object> payload) {
    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .post()
            .uri("/api/control/workflows/{workflowId}/nodes/{nodeId}/command", workflowId, nodeId)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    return response != null ? response.data() : Map.of();
  }

  /**
   * Gets execution progress.
   *
   * @param executionId the execution ID
   * @return progress data map
   */
  public Map<String, Object> getProgress(String executionId) {
    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .get()
            .uri("/api/control/executions/{executionId}/progress", executionId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    return response != null ? response.data() : Map.of();
  }

  /**
   * Gets session execution history.
   *
   * @param sessionId the session ID
   * @return list of history records
   */
  public List<Map<String, Object>> getHistory(String sessionId) {
    ApiResponse<List<Map<String, Object>>> response =
        daemonWebClient
            .get()
            .uri("/api/control/sessions/{sessionId}/history", sessionId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>>() {})
            .block();

    return response != null ? response.data() : List.of();
  }

  /**
   * Streams execution progress in real-time.
   *
   * @param executionId the execution ID
   * @param lineConsumer consumer for progress lines
   */
  public void streamProgress(String executionId, Consumer<String> lineConsumer) {
    daemonWebClient
        .get()
        .uri("/api/control/executions/{executionId}/progress/stream", executionId)
        .retrieve()
        .bodyToFlux(String.class)
        .doOnNext(lineConsumer)
        .blockLast();
  }

  /**
   * Streams execution logs in real-time.
   *
   * @param executionId the execution ID
   * @param lineConsumer consumer for log lines
   */
  public void streamLogs(String executionId, Consumer<String> lineConsumer) {
    daemonWebClient
        .get()
        .uri("/api/control/executions/{executionId}/logs/stream", executionId)
        .retrieve()
        .bodyToFlux(String.class)
        .doOnNext(lineConsumer)
        .blockLast();
  }

  /**
   * Gets session details.
   *
   * @param sessionId the session ID
   * @return session details map
   */
  public Map<String, Object> getSessionDetails(String sessionId) {
    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .get()
            .uri("/api/sessions/{sessionId}", sessionId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    return response != null ? response.data() : Map.of();
  }

  /**
   * Gets workflow details.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @return workflow details map
   */
  public Map<String, Object> getWorkflow(String sessionId, String workflowId) {
    ApiResponse<Map<String, Object>> response =
        daemonWebClient
            .get()
            .uri("/api/sessions/{sessionId}/workflows/{workflowId}", sessionId, workflowId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {})
            .block();

    return response != null ? response.data() : Map.of();
  }
}
