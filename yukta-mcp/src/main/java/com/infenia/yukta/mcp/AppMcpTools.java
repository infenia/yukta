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
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpTool;
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

  /** Description for session ID parameter. */
  private static final String SESSION_ID_DESC = "The unique identifier of the session";

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
  @McpTool(
      name = "get_session_details",
      description = "Get details of a specific Yukta session including workflow IDs")
  public Mono<SessionDetails> getSessionDetails(
      @McpArg(description = SESSION_ID_DESC) final String sessionId) {
    return sessionInfoProvider.getSessionDetails(sessionId);
  }

  /**
   * List all available sessions.
   *
   * @return Flux of session information
   */
  @McpTool(name = "list_sessions", description = "List all available Yukta sessions with metadata")
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
  @McpTool(
      name = "stream_session_logs",
      description =
          "Stream session logs with optional filtering by workflow, execution, or pattern")
  public Flux<String> streamSessionLogs(
      @McpArg(description = SESSION_ID_DESC) final String sessionId,
      @McpArg(description = "Optional workflow identifier to filter logs") final String workflowId,
      @McpArg(description = "Optional execution identifier to filter logs")
          final String executionId,
      @McpArg(description = "Optional regex pattern to filter log content")
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
  @McpTool(
      name = "get_workflow_execution_logs",
      description = "Get all logs for a specific workflow execution with optional filtering")
  public Mono<String> getWorkflowExecutionLogs(
      @McpArg(description = SESSION_ID_DESC) final String sessionId,
      @McpArg(description = "The unique identifier of the workflow execution")
          final String executionId,
      @McpArg(description = "Optional regex pattern to filter log content")
          final String filterPattern) {
    return logProvider.getWorkflowExecutionLogs(sessionId, executionId, filterPattern);
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  @McpTool(
      name = "get_workflow_details",
      description = "Get the full DAG definition (nodes and edges) of a Yukta workflow")
  public Mono<WorkflowDefinition> getWorkflowDetails(
      @McpArg(description = SESSION_ID_DESC) final String sessionId,
      @McpArg(description = "The unique identifier of the workflow") final String workflowId) {
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
  @McpTool(
      name = "trigger_workflow",
      description = "Trigger a Yukta workflow with an optional JSON payload")
  public Mono<String> triggerWorkflow(
      @McpArg(description = SESSION_ID_DESC) final String sessionId,
      @McpArg(description = "The unique identifier of the workflow to trigger")
          final String workflowId,
      @McpArg(description = "Optional JSON payload to pass to the workflow trigger")
          final String payloadJson) {
    return workflowExecutionProvider.triggerWorkflow(sessionId, workflowId, payloadJson);
  }

  /**
   * Get status of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return Mono containing concise workflow execution summary
   */
  @McpTool(
      name = "get_workflow_status",
      description = "Get the current high-level status of a workflow execution")
  public Mono<WorkflowExecutionSummary> getWorkflowStatus(
      @McpArg(description = SESSION_ID_DESC) final String sessionId,
      @McpArg(description = "The unique identifier of the workflow execution")
          final String executionId) {
    return workflowExecutionProvider.getWorkflowStatus(sessionId, executionId);
  }

  /**
   * List available plugins.
   *
   * @return list of plugin summaries
   */
  @McpTool(name = "list_plugins", description = "List all available Yukta workflow plugins")
  public Flux<PluginSummary> listPlugins() {
    return Flux.fromIterable(pluginInfoProvider.listPlugins());
  }

  /**
   * Get plugin details.
   *
   * @param type the plugin type
   * @return plugin details
   */
  @McpTool(
      name = "get_plugin_details",
      description = "Get full details of a specific Yukta plugin including usage pattern")
  public Mono<PluginDetails> getPluginDetails(
      @McpArg(description = "The unique plugin type/identifier") final String type) {
    return Mono.fromCallable(() -> pluginInfoProvider.getPluginDetails(type));
  }

  /**
   * Get control bus status with comprehensive monitoring information.
   *
   * @param filterType optional filter ("sessions", "plugins", "health", "executions")
   * @return ControlBusStatus with monitoring data
   */
  @McpTool(
      name = "get_control_bus_status",
      description = "Get comprehensive control bus status with monitoring information")
  public Mono<ControlBusStatus> getControlBusStatus(
      @McpArg(description = "Optional filter: sessions, plugins, health, or executions")
          final String filterType) {
    return Mono.fromCallable(() -> systemHealthProvider.getControlBusStatus(filterType));
  }

  /**
   * Get comprehensive instructions on how to create a new Yukta session with workflows and plugins.
   *
   * @return SessionCreationGuide with detailed guidance
   */
  @McpTool(
      name = "get_session_creation_instructions",
      description =
          "Get comprehensive instructions on how to create a new Yukta session with "
              + "workflows and plugins")
  public Mono<SessionCreationGuide> getSessionCreationInstructions() {
    return Mono.fromCallable(sessionInfoProvider::getSessionCreationInstructions);
  }

  /**
   * Create a new Yukta session with the provided configuration JSON.
   *
   * @param sessionConfigJson JSON string containing session configuration with sessionId,
   *     workflows, and other configuration details
   * @return Mono containing SessionCreationResponse with sessionId, created workflows, warnings,
   *     and success status
   */
  @McpTool(
      name = "create_session",
      description = "Create a new Yukta session with the provided configuration JSON")
  public Mono<SessionCreationResponse> createSession(
      @McpArg(
              description =
                  "JSON string containing session configuration (sessionId, workflows, etc.)")
          final String sessionConfigJson) {
    return sessionInfoProvider.createSession(sessionConfigJson);
  }

  /**
   * Get comprehensive plugin creation guide.
   *
   * @param templateType optional filter ("trigger", "processor", "terminal", "all")
   * @return PluginCreationGuide with comprehensive guidance
   */
  @McpTool(
      name = "get_plugin_creation_guide",
      description = "Get comprehensive guide for creating Yukta plugins with templates")
  public Mono<PluginCreationGuide> getPluginCreationGuide(
      @McpArg(description = "Template type filter: trigger, processor, terminal, or all")
          final String templateType) {
    return Mono.fromCallable(() -> pluginInfoProvider.getPluginCreationGuide(templateType));
  }
}
