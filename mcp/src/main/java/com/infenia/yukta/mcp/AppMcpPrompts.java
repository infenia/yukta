// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** MCP Prompts for Yukta. Provides interaction templates for AI agents. */
@Component
public class AppMcpPrompts {

  /** Default constructor. */
  public AppMcpPrompts() {
    // No-op
  }

  /**
   * Help the agent debug a failing workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return a prompt for debugging
   */
  @McpPrompt(
      name = "debug-workflow",
      description = "Help the agent debug a failing workflow execution")
  public Mono<String> debugWorkflow(
      @McpArg(description = "The session ID") final String sessionId,
      @McpArg(description = "The execution ID that failed") final String executionId) {
    return Mono.just(
        String.format(
            "I need help debugging the workflow execution %s in session %s. "
                + "Use get_workflow_status to inspect the per-node task progress, "
                + "get_execution_logs (with a filterPattern like 'ERROR' if helpful) to "
                + "analyze the logs, and get_workflow_details to review the DAG definition. "
                + "Identify the root cause of the failure and suggest a fix. If a retry is "
                + "appropriate, use control_workflow with action RESTART or "
                + "RESTART_FROM_NODE.",
            executionId, sessionId));
  }

  /**
   * Help the agent generate a valid session configuration JSON.
   *
   * @return a prompt for session configuration
   */
  @McpPrompt(
      name = "create-session-config",
      description = "Help the agent generate a valid session configuration JSON")
  public Mono<String> createSessionConfig() {
    return Mono.just(
        "Please help me generate a valid JSON configuration for a new Yukta session. First "
            + "call get_session_creation_instructions for the configuration format and "
            + "list_plugins to see the available node types, then draft workflow definitions "
            + "with nodes and edges and apply them with create_session.");
  }
}
