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
        Yukta (Sanskrit for 'Combined' or 'Joined') is a high-performance orchestration server
        built for AI-driven development. It provides a robust DAG (Directed Acyclic Graph)
        workflow engine that enables AI agents to trigger and monitor code quality checks,
        security scans, and other build-time automations.

        Architecture:
        Yukta is built with Java 21+ and Spring Boot WebFlux (Project Reactor), providing
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
        Yukta represents every quality check or automation as a Directed Acyclic Graph (DAG).
        - Nodes: Individual processing steps.
        - Edges: Dependencies and data flow between nodes.

        ## Reactive Core
        The server is 100% non-blocking using Project Reactor's Flux and Mono.
        """);
  }

  /**
   * Retrieves full logs for a specific session.
   *
   * @param sessionId the unique identifier of the session
   * @return a Mono containing the joined session logs
   */
  @McpResource(
      uri = "yukta://sessions/{sessionId}/logs",
      name = "Session Logs",
      description = "Full logs for a specific session")
  public Mono<String> getSessionLogs(final String sessionId) {
    return logProvider
        .streamSessionLogs(sessionId, null, null, null)
        .collectList()
        .map(logs -> String.join("\n", logs));
  }
}
