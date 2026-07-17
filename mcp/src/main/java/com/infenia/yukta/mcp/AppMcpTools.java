// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.ControlBusStatus;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.PluginCreationGuide;
import com.infenia.yukta.mcp.dto.PluginDetails;
import com.infenia.yukta.mcp.dto.PluginList;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionList;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.mcp.dto.WorkflowHistory;
import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import com.infenia.yukta.mcp.provider.DefaultPluginInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSessionInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSystemHealthProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowControlProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowExecutionProvider;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP (Model Context Protocol) tools for Yukta. Provides facade for interacting with workflows,
 * sessions, and plugins by delegating to specialized provider classes.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.CouplingBetweenObjects"})
public class AppMcpTools {

  /** Description for session ID parameter. */
  private static final String SESSION_ID_DESC = "The unique identifier of the session";

  /** Provides session information for MCP tools. */
  private final DefaultSessionInfoProvider sessionInfoProvider;

  /** Provides log data for MCP tools. */
  private final DefaultLogProvider logProvider;

  /** Provides workflow execution information for MCP tools. */
  private final DefaultWorkflowExecutionProvider workflowExecutionProvider;

  /** Provides workflow and node control operations for MCP tools. */
  private final DefaultWorkflowControlProvider workflowControlProvider;

  /** Provides plugin information for MCP tools. */
  private final DefaultPluginInfoProvider pluginInfoProvider;

  /** Provides system health information for MCP tools. */
  private final DefaultSystemHealthProvider systemHealthProvider;

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return Mono containing session details
   */
  @McpTool(
      name = "get_session_details",
      title = "Get Session Details",
      description = "Get details of a specific Yukta session including workflow IDs",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<SessionDetails> getSessionDetails(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId) {
    return sessionInfoProvider.getSessionDetails(sessionId);
  }

  /**
   * List all available sessions.
   *
   * @return Mono containing the list of session summaries
   */
  @McpTool(
      name = "list_sessions",
      title = "List Sessions",
      description = "List all available Yukta sessions with their workflow counts",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<SessionList> listSessions() {
    return sessionInfoProvider.listSessions().map(SessionList::new);
  }

  /**
   * Get the persisted logs of a workflow execution.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param tailLines optional maximum number of trailing lines to return
   * @param filterPattern optional regex applied to each log line
   * @return Mono containing the execution logs
   */
  @McpTool(
      name = "get_execution_logs",
      title = "Get Execution Logs",
      description =
          "Get the persisted plugin logs of a workflow execution. Supports an optional regex "
              + "filter and tailLines to return only the last N matching lines. Poll together "
              + "with get_workflow_status while an execution is running.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<ExecutionLogs> getExecutionLogs(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(
              required = true,
              description = "The unique identifier of the workflow execution")
          final String executionId,
      @McpToolParam(required = false, description = "Return only the last N matching log lines")
          final Integer tailLines,
      @McpToolParam(required = false, description = "Optional regex pattern to filter log lines")
          final String filterPattern) {
    return logProvider.getExecutionLogs(sessionId, executionId, tailLines, filterPattern);
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
      title = "Get Workflow Details",
      description = "Get the full DAG definition (nodes and edges) of a Yukta workflow",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<WorkflowDefinition> getWorkflowDetails(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(required = true, description = "The unique identifier of the workflow")
          final String workflowId) {
    return workflowExecutionProvider.getWorkflowDetails(sessionId, workflowId);
  }

  /**
   * Start a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the start result with the new execution ID
   */
  @McpTool(
      name = "start_workflow",
      title = "Start Workflow",
      description =
          "Start a Yukta workflow execution. Returns the execution ID to use with "
              + "get_workflow_status and get_execution_logs.",
      generateOutputSchema = true,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              openWorldHint = false))
  public Mono<WorkflowStartResult> startWorkflow(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(required = true, description = "The unique identifier of the workflow to start")
          final String workflowId) {
    return workflowExecutionProvider.startWorkflow(sessionId, workflowId);
  }

  /**
   * Get the current progress snapshot of a workflow execution.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @return Mono containing the current workflow progress including per-node task status
   */
  @McpTool(
      name = "get_workflow_status",
      title = "Get Workflow Status",
      description =
          "Get the current progress snapshot of a workflow execution, including overall "
              + "status, per-node task progress, and start/end times. Poll this tool to track "
              + "a running execution.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<WorkflowProgress> getWorkflowStatus(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(
              required = true,
              description = "The unique identifier of the workflow execution")
          final String executionId) {
    return workflowExecutionProvider.getWorkflowStatus(sessionId, executionId);
  }

  /**
   * Get the execution history of a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the list of execution summaries for the session
   */
  @McpTool(
      name = "get_workflow_history",
      title = "Get Workflow History",
      description =
          "List all workflow executions of a session with their status and start/end times",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<WorkflowHistory> getWorkflowHistory(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId) {
    return workflowExecutionProvider.getWorkflowHistory(sessionId).map(WorkflowHistory::new);
  }

  /**
   * Execute a workflow-level control action.
   *
   * @param sessionId the session that owns the execution
   * @param action the control action to execute
   * @param executionId the target execution (all actions except stop_all)
   * @param workflowId the target workflow (stop_all only)
   * @param fromNodeId the node to restart from (restart_from_node only)
   * @param reason optional reason recorded with stop actions
   * @return Mono containing the action result
   */
  @McpTool(
      name = "control_workflow",
      title = "Control Workflow",
      description =
          "Control a workflow execution: PAUSE, RESUME, STOP, or RESTART an execution; "
              + "RESTART_FROM_NODE to restart from a specific node (requires fromNodeId); "
              + "STOP_ALL to stop every active execution of a workflow (requires workflowId, "
              + "no executionId). Restart actions return the new execution ID in "
              + "resultExecutionIds.",
      generateOutputSchema = true,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = true,
              openWorldHint = false))
  public Mono<ControlActionResult> controlWorkflow(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(
              required = true,
              description =
                  "The control action: PAUSE, RESUME, STOP, STOP_ALL, RESTART, or "
                      + "RESTART_FROM_NODE")
          final WorkflowControlAction action,
      @McpToolParam(
              required = false,
              description =
                  "The unique identifier of the workflow execution (required for all actions "
                      + "except STOP_ALL)")
          final String executionId,
      @McpToolParam(
              required = false,
              description = "The unique identifier of the workflow (required for STOP_ALL)")
          final String workflowId,
      @McpToolParam(
              required = false,
              description = "The node ID to restart from (required for RESTART_FROM_NODE)")
          final String fromNodeId,
      @McpToolParam(required = false, description = "Optional reason recorded with stop actions")
          final String reason) {
    return workflowControlProvider.controlWorkflow(
        sessionId, action, executionId, workflowId, fromNodeId, reason);
  }

  /**
   * Execute a node-level control action.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the target execution
   * @param nodeId the target node
   * @param action the control action to execute
   * @param immediate whether a STOP action interrupts the node immediately
   * @param reason optional reason recorded with stop actions
   * @return Mono containing the action result
   */
  @McpTool(
      name = "control_node",
      title = "Control Node",
      description =
          "Control a single node within a running workflow execution: PAUSE, RESUME, or STOP "
              + "the node; SKIP/UNSKIP to toggle skipping; STEP_ENABLE/STEP_DISABLE to toggle "
              + "step-by-step mode and STEP to execute one step.",
      generateOutputSchema = true,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = true,
              openWorldHint = false))
  public Mono<ControlActionResult> controlNode(
      @McpToolParam(required = true, description = SESSION_ID_DESC) final String sessionId,
      @McpToolParam(
              required = true,
              description = "The unique identifier of the workflow execution")
          final String executionId,
      @McpToolParam(required = true, description = "The unique identifier of the node")
          final String nodeId,
      @McpToolParam(
              required = true,
              description =
                  "The control action: PAUSE, RESUME, STOP, SKIP, UNSKIP, STEP, STEP_ENABLE, "
                      + "or STEP_DISABLE")
          final NodeControlAction action,
      @McpToolParam(
              required = false,
              description = "For STOP: interrupt the node immediately instead of gracefully")
          final Boolean immediate,
      @McpToolParam(required = false, description = "Optional reason recorded with stop actions")
          final String reason) {
    return workflowControlProvider.controlNode(
        sessionId, executionId, nodeId, action, immediate, reason);
  }

  /**
   * List available plugins.
   *
   * @return Mono containing the list of plugin summaries
   */
  @McpTool(
      name = "list_plugins",
      title = "List Plugins",
      description = "List all available Yukta workflow plugins with their categories",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<PluginList> listPlugins() {
    return Mono.fromCallable(pluginInfoProvider::listPlugins)
        .subscribeOn(Schedulers.boundedElastic())
        .map(PluginList::new);
  }

  /**
   * Get plugin details.
   *
   * @param type the plugin type
   * @return plugin details
   */
  @McpTool(
      name = "get_plugin_details",
      title = "Get Plugin Details",
      description = "Get full details of a specific Yukta plugin including usage pattern",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<PluginDetails> getPluginDetails(
      @McpToolParam(required = true, description = "The unique plugin type/identifier")
          final String type) {
    return Mono.fromCallable(() -> pluginInfoProvider.getPluginDetails(type))
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Get control bus status with comprehensive monitoring information.
   *
   * @param filterType optional filter ("sessions", "plugins", "health", "executions")
   * @return ControlBusStatus with monitoring data
   */
  @McpTool(
      name = "get_control_bus_status",
      title = "Get Control Bus Status",
      description = "Get comprehensive control bus status with monitoring information",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<ControlBusStatus> getControlBusStatus(
      @McpToolParam(
              required = false,
              description = "Optional filter: sessions, plugins, health, or executions")
          final String filterType) {
    return systemHealthProvider.getControlBusStatus(filterType);
  }

  /**
   * Get comprehensive instructions on how to create a new Yukta session with workflows and plugins.
   *
   * @return SessionCreationGuide with detailed guidance
   */
  @McpTool(
      name = "get_session_creation_instructions",
      title = "Get Session Creation Instructions",
      description =
          "Get comprehensive instructions on how to create a new Yukta session with "
              + "workflows and plugins",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<SessionCreationGuide> getSessionCreationInstructions() {
    return Mono.fromCallable(sessionInfoProvider::getSessionCreationInstructions);
  }

  /**
   * Create a new Yukta session with the provided configuration JSON.
   *
   * @param sessionConfigJson JSON string containing session configuration with sessionId,
   *     workflows, and other configuration details
   * @return Mono containing SessionCreationResult with sessionId, created workflows, warnings, and
   *     success status
   */
  @McpTool(
      name = "create_session",
      title = "Create Session",
      description = "Create a new Yukta session with the provided configuration JSON",
      generateOutputSchema = true,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              openWorldHint = false))
  public Mono<SessionCreationResult> createSession(
      @McpToolParam(
              required = true,
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
      title = "Get Plugin Creation Guide",
      description = "Get comprehensive guide for creating Yukta plugins with templates",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
  public Mono<PluginCreationGuide> getPluginCreationGuide(
      @McpToolParam(
              required = false,
              description = "Template type filter: trigger, processor, terminal, or all")
          final String templateType) {
    return Mono.fromCallable(() -> pluginInfoProvider.getPluginCreationGuide(templateType));
  }
}
