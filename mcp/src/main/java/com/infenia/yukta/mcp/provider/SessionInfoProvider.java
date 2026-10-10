// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.PluginReference;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionSummary;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Handles session operations including retrieval, listing, creation, and comprehensive guidance
 * documentation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionInfoProvider {

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return Mono containing session details; errors if the session is unknown
   */
  public Mono<SessionDetails> getSessionDetails(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .map(config -> new SessionDetails(sessionId, List.copyOf(config.workflows().keySet())))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Session not found: "
                            + sessionId
                            + ". Use list_sessions to see available sessions.")));
  }

  /**
   * List all available sessions.
   *
   * @return Mono containing the list of session summaries
   */
  public Mono<List<SessionSummary>> listSessions() {
    return sessionService
        .getSessionIds()
        .flatMap(
            sessionId ->
                sessionService
                    .getSessionConfig(sessionId)
                    .map(config -> new SessionSummary(sessionId, config.workflows().size()))
                    .onErrorResume(
                        e -> {
                          log.atWarn()
                              .setCause(e)
                              .log(
                                  "Failed to load session config for {}: {}",
                                  sessionId,
                                  e.getMessage());
                          return Mono.empty();
                        }))
        .collectList();
  }

  /**
   * Get comprehensive instructions for creating a new session.
   *
   * @return session creation guide
   */
  public SessionCreationGuide getSessionCreationInstructions() {
    final String namingConventions =
        """
        Session IDs must be alphanumeric lowercase with hyphens (e.g., 'my-session-001'). \
        Workflow names should follow camelCase or kebab-case (e.g., 'quality-checks', \
        'codeReview'). Use descriptive names that indicate the workflow's purpose.\
        """;

    final String configurationStructure =
        """
        A session configuration is a JSON object with a 'workflows' key containing \
        workflow definitions. Each workflow definition includes: 'nodes' (array of node \
        definitions) and 'edges' (array of directed connections between nodes). \
        Each node has 'id', 'type' (plugin type), and 'config' (plugin-specific settings).\
        """;

    final String exampleSessionConfig =
        """
        {
          "workflows": {
            "quality-checks": {
              "nodes": [
                {
                  "id": "trigger-check",
                  "type": "webhook-trigger",
                  "config": { "port": 8080 }
                },
                {
                  "id": "run-gradle",
                  "type": "gradle-checker",
                  "config": { "tasks": ["test", "check"] }
                },
                {
                  "id": "log-results",
                  "type": "log-aggregator",
                  "config": { "format": "json" }
                }
              ],
              "edges": [
                { "from": "trigger-check", "to": "run-gradle", "port": "default" },
                { "from": "run-gradle", "to": "log-results", "port": "default" }
              ]
            }
          }
        }\
        """;

    final String workflowDefinitionFormat =
        """
        A workflow definition is a Directed Acyclic Graph (DAG) where: \
        Nodes represent processing steps (trigger, processor, or terminal plugins). \
        Each node has a unique 'id', a 'type' (matching a registered plugin), and \
        'config' for plugin-specific parameters. Edges connect nodes using 'from' \
        (source node id), 'to' (target node id), and 'port' (output port name). \
        Cycles are not allowed. Execution flows from trigger nodes through processors \
        to terminal nodes.\
        """;

    final List<PluginReference> availablePlugins =
        registry.listPlugins().stream()
            .map(
                p ->
                    new PluginReference(
                        p.getType(), p.getCategory().toString(), p.describe().description()))
            .toList();

    final List<String> commonErrors =
        List.of(
            describe(
                "Plugin Not Found",
                "Used a plugin type that is not registered in the PluginRegistry. "
                    + "For example, referencing 'invalid-plugin' when only 'gradle-checker' is "
                    + "available.",
                "Check the available plugins using the list_plugins tool. Ensure the 'type' "
                    + "field in your node configuration matches exactly (case-sensitive) with a "
                    + "registered plugin."),
            describe(
                "Cyclic DAG Detected",
                "The workflow definition contains a cycle (e.g., Node A → B → A), which "
                    + "violates the DAG (Directed Acyclic Graph) requirement.",
                "Review your edges configuration and ensure no node can be reached from itself "
                    + "by following the edges forward. Use acyclic topological ordering."),
            describe(
                "Missing Required Configuration Fields",
                "A plugin node is missing required configuration fields. For example, a "
                    + "gradle-checker node missing the 'tasks' array in its config.",
                "Review the plugin's usage pattern using the get_plugin_details tool and ensure "
                    + "all required configuration fields are present in the node's config."));

    return new SessionCreationGuide(
        namingConventions,
        configurationStructure,
        exampleSessionConfig,
        workflowDefinitionFormat,
        availablePlugins,
        commonErrors);
  }

  /**
   * Create a new session with the provided configuration.
   *
   * @param config the session configuration
   * @return Mono containing the session creation result
   */
  public Mono<SessionCreationResult> createSession(final SessionConfigData config) {
    return sessionService
        .applyConfig(config)
        .thenReturn(
            new SessionCreationResult(
                config.sessionId(), List.copyOf(config.workflows().keySet()), List.of(), true));
  }

  private static String describe(final String error, final String cause, final String resolution) {
    return error + ": " + cause + " Fix: " + resolution;
  }
}
