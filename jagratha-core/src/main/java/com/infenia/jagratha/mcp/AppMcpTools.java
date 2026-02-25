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
package com.infenia.jagratha.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.model.PluginDetails;
import com.infenia.jagratha.model.PluginSummary;
import com.infenia.jagratha.model.SessionDetails;
import com.infenia.jagratha.model.SessionSummary;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowExecutionSummary;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import com.infenia.jagratha.service.WorkflowRegistry;
import com.infenia.jagratha.service.WorkflowService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * MCP (Model Context Protocol) tools for Jagratha. Provides tools for interacting with workflows,
 * sessions, and plugins.
 */
@Component
@RequiredArgsConstructor
public class AppMcpTools {

  private final WorkflowService workflowService;
  private final SessionService sessionService;
  private final TaskTrackerService trackerService;
  private final WorkflowRegistry registry;
  private final ObjectMapper objectMapper;

  /**
   * List all active sessions.
   *
   * @return Mono containing a list of session summaries
   */
  @Tool(description = "List all active Jagratha sessions with their summaries")
  @SuppressWarnings("unchecked")
  public Mono<List<SessionSummary>> listSessions() {
    return sessionService
        .getActiveSessions()
        .flatMap(
            id ->
                sessionService
                    .getSessionConfig(id)
                    .map(
                        config -> {
                          final List<WorkflowExecutionSummary> history =
                              trackerService.getHistory(id);
                          final LocalDateTime lastActive =
                              history.isEmpty() ? null : history.get(0).startTime();
                          return new SessionSummary(
                              id,
                              (String) config.getOrDefault("initiator", ""),
                              (String) config.getOrDefault("initiatedTime", ""),
                              lastActive,
                              (String) config.getOrDefault("description", ""),
                              (Map<String, String>) config.getOrDefault("tags", Map.of()));
                        }))
        .collectList();
  }

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return Mono containing session details
   */
  @Tool(description = "Get details of a specific Jagratha session including workflow IDs")
  @SuppressWarnings("unchecked")
  public Mono<SessionDetails> getSessionDetails(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .map(
            config -> {
              final Map<String, Object> workflows =
                  (Map<String, Object>) config.getOrDefault("workflows", Map.of());
              return new SessionDetails(sessionId, List.copyOf(workflows.keySet()));
            });
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  @Tool(description = "Get the full DAG definition (nodes and edges) of a Jagratha workflow")
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService.getSessionWorkflow(sessionId, workflowId);
  }

  /**
   * Trigger a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payloadJson optional JSON string for trigger payload
   * @return Mono containing the execution ID
   */
  @Tool(description = "Trigger a Jagratha workflow with an optional JSON payload")
  public Mono<String> triggerWorkflow(
      final String sessionId, final String workflowId, final String payloadJson) {
    final Mono<String> result;
    if (payloadJson != null && !payloadJson.isBlank()) {
      result = parseAndTrigger(sessionId, workflowId, payloadJson);
    } else {
      result =
          Mono.just(workflowService.runWorkflow(sessionId, workflowId, Map.of()).executionId());
    }
    return result;
  }

  private Mono<String> parseAndTrigger(
      final String sessionId, final String workflowId, final String payloadJson) {
    Mono<String> result;
    try {
      final Map<String, Object> payload =
          objectMapper.readValue(payloadJson, new TypeReference<>() {});
      result = Mono.just(workflowService.runWorkflow(sessionId, workflowId, payload).executionId());
    } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
      result = Mono.error(new IllegalArgumentException("Invalid JSON payload: " + e.getMessage()));
    }
    return result;
  }

  /**
   * Get status of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return Mono containing concise workflow execution summary
   */
  @Tool(description = "Get the current high-level status of a workflow execution")
  public Mono<WorkflowExecutionSummary> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return Mono.fromCallable(() -> trackerService.getHistory(sessionId))
        .flatMapIterable(list -> list)
        .filter(s -> s.executionId().equals(executionId))
        .next();
  }

  /**
   * List available plugins.
   *
   * @return list of plugin summaries
   */
  @Tool(description = "List all available Jagratha workflow plugins")
  public List<PluginSummary> listPlugins() {
    return registry.listPlugins().stream()
        .map(p -> new PluginSummary(p.getType(), p.getCategory()))
        .toList();
  }

  /**
   * Get plugin details.
   *
   * @param type the plugin type
   * @return plugin details
   */
  @Tool(description = "Get full details of a specific Jagratha plugin including usage pattern")
  public PluginDetails getPluginDetails(final String type) {
    final WorkflowPlugin plugin = registry.get(type);
    if (plugin == null) {
      throw new IllegalArgumentException("Plugin not found: " + type);
    }
    return new PluginDetails(
        plugin.getType(),
        plugin.getCategory(),
        plugin.getDescription(),
        plugin.getUsagePattern(),
        plugin.getUiDesign().orElse(null),
        plugin.getOutputPorts());
  }
}
