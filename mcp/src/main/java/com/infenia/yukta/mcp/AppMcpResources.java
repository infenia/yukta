// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** MCP Resources for Yukta. Provides documentation and logs via URI templates. */
@Component
@RequiredArgsConstructor
public class AppMcpResources {

  /** Provides log data for MCP resources. */
  private final DefaultLogProvider logProvider;

  /**
   * Provides a high-level overview of Yukta's capabilities and architecture.
   *
   * @return a Mono containing the overview text
   */
  @McpResource(
      uri = "yukta://overview",
      name = "Yukta Overview",
      description = "High-level overview of Yukta's capabilities and architecture")
  public Mono<String> getYuktaOverview() {
    return Mono.just(
        """
        Yukta (Sanskrit for 'Combined' or 'Joined') is a reactive, DAG-based workflow
        orchestration server. Workflows are defined once as JSON (nodes and edges) inside a
        session and can be run via REST, a web UI, or this MCP server. Built-in
        Trigger/Processor/Terminal plugins cover CI/CD, data pipelines, and quality gates,
        and the plugin API allows custom extensions.

        Architecture:
        Yukta is built with Java 25 and Spring Boot WebFlux (Project Reactor). A control bus
        orchestrates workflow executions and exposes lifecycle control (start, pause, resume,
        stop, restart, per-node signals), progress snapshots, execution history, and
        persistent plugin logs.

        Typical MCP flow:
        1. list_sessions / create_session to find or create a session.
        2. get_workflow_details to inspect a workflow DAG.
        3. start_workflow to run it, then poll get_workflow_status and get_execution_logs.
        4. control_workflow / control_node for pause, resume, stop, restart, skip, and
           step-mode debugging.
        """);
  }

  /**
   * Provides a detailed deep dive into Yukta's DAG and Reactive architecture.
   *
   * @return a Mono containing the architecture documentation
   */
  @McpResource(
      uri = "yukta://architecture",
      name = "Yukta Architecture",
      description = "Detailed deep dive into Yukta's DAG and Reactive architecture")
  public Mono<String> getYuktaArchitectureDocs() {
    return Mono.just(
        """
        # Yukta Architecture Deep Dive

        ## DAG-based Workflow Engine
        Yukta represents every workflow as a Directed Acyclic Graph (DAG).
        - Nodes: individual processing steps backed by plugins (Trigger, Processor, Terminal).
        - Edges: dependencies and data flow between nodes, connected via named output ports.

        ## Sessions
        Workflows live inside sessions. A session configuration (JSON) declares one or more
        workflow definitions; applying it compiles and caches each workflow for execution.

        ## Control Bus
        All execution control flows through a control bus gateway: starting and stopping
        executions, pause/resume at workflow or node level, skip and step-mode signals,
        progress snapshots, execution history, and live log observation.

        ## Reactive Core
        The server is 100% non-blocking using Project Reactor's Flux and Mono on Spring Boot
        WebFlux. The MCP server runs the reactive ASYNC stack with the stateless
        streamable-HTTP transport.

        ## Plugin Logs
        Plugin output is persisted per execution in a pluggable log store and is available
        via the get_execution_logs tool and the
        yukta://sessions/{sessionId}/executions/{executionId}/logs resource.
        """);
  }

  /**
   * Retrieves the persisted logs of a workflow execution.
   *
   * @param sessionId the unique identifier of the session owning the execution
   * @param executionId the unique identifier of the execution
   * @return a Mono containing the joined execution logs
   */
  @McpResource(
      uri = "yukta://sessions/{sessionId}/executions/{executionId}/logs",
      name = "Execution Logs",
      description = "Persisted plugin logs of a workflow execution")
  public Mono<String> getExecutionLogs(final String sessionId, final String executionId) {
    return logProvider
        .getExecutionLogs(sessionId, executionId, null, null)
        .map(logs -> String.join("\n", logs.lines()));
  }
}
