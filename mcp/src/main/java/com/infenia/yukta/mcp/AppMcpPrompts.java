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
                + "Please analyze the logs and the DAG definition to identify the "
                + "root cause of the failure.",
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
        "Please help me generate a valid JSON configuration for a new Yukta session. I need to"
            + " include workflow definitions with nodes and edges for code quality checks.");
  }
}
