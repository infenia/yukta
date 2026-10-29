// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlActionResult;
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
   * Delete a session with its workflows; their run history stays.
   *
   * @param sessionId the session
   * @param force cancel active runs first instead of refusing
   * @return the result; errors if the session is unknown or has active runs and force is false
   */
  public Mono<ControlActionResult> deleteSession(final String sessionId, final boolean force) {
    return sessionService
        .deleteSession(sessionId, force)
        .thenReturn(
            new ControlActionResult(
                "DELETE_SESSION", null, null, List.of(), "Deleted session " + sessionId));
  }

  /**
   * Delete a workflow of a session; its run history stays.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param force cancel active runs first instead of refusing
   * @return the result; errors if the workflow is unknown or has active runs and force is false
   */
  public Mono<ControlActionResult> deleteWorkflow(
      final String sessionId, final String workflowId, final boolean force) {
    return sessionService
        .deleteWorkflow(sessionId, workflowId, force)
        .thenReturn(
            new ControlActionResult(
                "DELETE_WORKFLOW",
                null,
                null,
                List.of(),
                "Deleted workflow " + workflowId + " of session " + sessionId));
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
        A session configuration is a JSON object with 'sessionId', 'name', 'description', \
        'initiator', 'projectPath' (an existing directory) and a 'workflows' object that maps a \
        workflow id to its definition. Unknown properties are rejected, so use exactly the \
        field names of the example.\
        """;

    final String exampleSessionConfig =
        """
        {
          "sessionId": "demo",
          "name": "Quickstart",
          "description": "Greet a user",
          "initiator": "me",
          "projectPath": "/tmp",
          "workflows": {
            "greet": {
              "workflowId": "greet",
              "description": "Map the request payload into a greeting",
              "nodes": [
                { "nodeId": "trigger", "type": "API_TRIGGER", "config": {} },
                {
                  "nodeId": "map",
                  "type": "MAPPER",
                  "config": {
                    "mode": "PROJECTION",
                    "mapping": { "greeting": "'Hello, ' + payload.name" }
                  }
                },
                { "nodeId": "end", "type": "CONSOLE_TERMINAL", "config": {} }
              ],
              "edges": [
                { "source": "trigger", "target": "map" },
                { "source": "map", "target": "end" }
              ]
            }
          }
        }\
        """;

    final String workflowDefinitionFormat =
        """
        A workflow definition has a 'workflowId', a 'description', 'nodes' and 'edges'. It is a \
        Directed Acyclic Graph: a node has a unique 'nodeId', a 'type' (the type of a registered \
        plugin, see list_plugins), a 'config' object for that plugin and, optionally, a 'group' \
        naming the stage it belongs to (display only). An edge has a 'source' \
        and a 'target' node id and, optionally, a 'sourcePort' (for example 'success', 'failure' \
        or 'error'; without one it uses the default port). Cycles are not allowed. Execution \
        flows from trigger nodes through processors to terminal nodes.\
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
                    + "For example, referencing 'invalid-plugin' when only 'MAPPER' is available.",
                "Check the available plugins using the list_plugins tool. Ensure the 'type' "
                    + "field in your node configuration matches exactly (case-sensitive) with a "
                    + "registered plugin."),
            describe(
                "Unknown Property",
                "The server rejects JSON properties it does not know, such as 'id', 'from', "
                    + "'to' or 'port' (the names are 'nodeId', 'source', 'target' and "
                    + "'sourcePort').",
                "Use exactly the field names of the example configuration."),
            describe(
                "Cyclic DAG Detected",
                "The workflow definition contains a cycle (e.g., Node A → B → A), which "
                    + "violates the DAG (Directed Acyclic Graph) requirement.",
                "Review your edges configuration and ensure no node can be reached from itself "
                    + "by following the edges forward. Use acyclic topological ordering."),
            describe(
                "Missing Required Configuration Fields",
                "A plugin node is missing required configuration fields. For example, a "
                    + "MAPPER node missing its 'mapping' in its config.",
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
