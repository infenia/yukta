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
package com.infenia.yukta.mcp;

import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import com.infenia.yukta.mcp.provider.DefaultPluginInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSessionInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSystemHealthProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowExecutionProvider;
import com.infenia.yukta.model.api.ControlBusStatus;
import com.infenia.yukta.model.api.PluginCreationGuide;
import com.infenia.yukta.model.api.PluginDetails;
import com.infenia.yukta.model.api.PluginSummary;
import com.infenia.yukta.model.api.SessionCreationGuide;
import com.infenia.yukta.model.api.SessionCreationResponse;
import com.infenia.yukta.model.api.SessionDetails;
import com.infenia.yukta.model.api.SessionInfo;
import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * MCP (Model Context Protocol) tools for Yukta. Provides facade for interacting with workflows,
 * sessions, and plugins by delegating to specialized provider classes.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.LongVariable", "PMD.UseObjectForClearerAPI"})
public class AppMcpTools {

  private final DefaultSessionInfoProvider sessionInfoProvider;
  private final DefaultLogProvider logProvider;
  private final DefaultWorkflowExecutionProvider workflowExecutionProvider;
  private final DefaultPluginInfoProvider pluginInfoProvider;
  private final DefaultSystemHealthProvider systemHealthProvider;

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return Mono containing session details
   */
  @Tool(description = "Get details of a specific Yukta session including workflow IDs")
  public Mono<SessionDetails> getSessionDetails(final String sessionId) {
    return sessionInfoProvider.getSessionDetails(sessionId);
  }

  /**
   * List all available sessions.
   *
   * @return Flux of session information
   */
  @Tool(description = "List all available Yukta sessions with metadata")
  public Flux<SessionInfo> listSessions() {
    return sessionInfoProvider.listSessions();
  }

  /**
   * Stream session logs with optional filtering.
   *
   * @param sessionId the session identifier
   * @param workflowId optional workflow filter
   * @param executionId optional execution filter
   * @param filterPattern optional regex pattern filter
   * @return Flux of log lines
   */
  @Tool(
      description =
          "Stream session logs with optional filtering by workflow, execution, or pattern")
  public Flux<String> streamSessionLogs(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final String filterPattern) {
    return logProvider.streamSessionLogs(sessionId, workflowId, executionId, filterPattern);
  }

  /**
   * Get workflow execution logs.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @param filterPattern optional regex pattern filter
   * @return Mono containing formatted logs
   */
  @Tool(description = "Get all logs for a specific workflow execution with optional filtering")
  public Mono<String> getWorkflowExecutionLogs(
      final String sessionId, final String executionId, final String filterPattern) {
    return logProvider.getWorkflowExecutionLogs(sessionId, executionId, filterPattern);
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  @Tool(description = "Get the full DAG definition (nodes and edges) of a Yukta workflow")
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return workflowExecutionProvider.getWorkflowDetails(sessionId, workflowId);
  }

  /**
   * Trigger a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payloadJson optional JSON string for trigger payload
   * @return Mono containing the execution ID
   */
  @Tool(description = "Trigger a Yukta workflow with an optional JSON payload")
  public Mono<String> triggerWorkflow(
      final String sessionId, final String workflowId, final String payloadJson) {
    return workflowExecutionProvider.triggerWorkflow(sessionId, workflowId, payloadJson);
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
    return workflowExecutionProvider.getWorkflowStatus(sessionId, executionId);
  }

  /**
   * List available plugins.
   *
   * @return list of plugin summaries
   */
  @Tool(description = "List all available Yukta workflow plugins")
  public java.util.List<PluginSummary> listPlugins() {
    return pluginInfoProvider.listPlugins();
  }

  /**
   * Get plugin details.
   *
   * @param type the plugin type
   * @return plugin details
   */
  @Tool(description = "Get full details of a specific Yukta plugin including usage pattern")
  public PluginDetails getPluginDetails(final String type) {
    return pluginInfoProvider.getPluginDetails(type);
  }

  /**
   * Get control bus status with comprehensive monitoring information.
   *
   * @param filterType optional filter ("sessions", "plugins", "health", "executions")
   * @return ControlBusStatus with monitoring data
   */
  @Tool(description = "Get comprehensive control bus status with monitoring information")
  public ControlBusStatus getControlBusStatus(final String filterType) {
    return systemHealthProvider.getControlBusStatus(filterType);
  }

  /**
   * Get comprehensive instructions on how to create a new Yukta session with workflows and plugins.
   *
   * @return SessionCreationGuide with detailed guidance
   */
  @Tool(
      description =
          "Get comprehensive instructions on how to create a new Yukta session with "
              + "workflows and plugins")
  public SessionCreationGuide getSessionCreationInstructions() {
    return sessionInfoProvider.getSessionCreationInstructions();
  }

  /**
   * Create a new Yukta session with the provided configuration JSON.
   *
   * @param sessionConfigJson JSON string containing session configuration with sessionId,
   *     workflows, and other configuration details
   * @return Mono containing SessionCreationResponse with sessionId, created workflows, warnings,
   *     and success status
   */
  @Tool(description = "Create a new Yukta session with the provided configuration JSON")
  public Mono<SessionCreationResponse> createSession(final String sessionConfigJson) {
    return sessionInfoProvider.createSession(sessionConfigJson);
  }

  /**
   * Get comprehensive plugin creation guide.
   *
   * @param templateType optional filter ("trigger", "processor", "terminal", "all")
   * @return PluginCreationGuide with comprehensive guidance
   */
  @Tool(description = "Get comprehensive guide for creating Yukta plugins with templates")
  public PluginCreationGuide getPluginCreationGuide(final String templateType) {
    return pluginInfoProvider.getPluginCreationGuide(templateType);
  }
}
