// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ErrorExample;
import com.infenia.yukta.mcp.dto.PluginReference;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionSummary;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Default implementation of SessionInfoProvider. Handles session operations including retrieval,
 * listing, creation, and comprehensive guidance documentation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSessionInfoProvider implements SessionInfoProvider {

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  /** Mapper for converting between JSON and Java objects. */
  private final ObjectMapper objectMapper;

  @Override
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

  @Override
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

  @Override
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
                        p.getType(), p.getCategory().toString(), p.getDescription()))
            .toList();

    final List<ErrorExample> commonErrors =
        List.of(
            new ErrorExample(
                "Plugin Not Found",
                "Used a plugin type that is not registered in the PluginRegistry. "
                    + "For example, referencing 'invalid-plugin' when only 'gradle-checker' is "
                    + "available.",
                "Check the available plugins using the list_plugins tool. Ensure the 'type' "
                    + "field in your node configuration matches exactly (case-sensitive) with a "
                    + "registered plugin."),
            new ErrorExample(
                "Cyclic DAG Detected",
                "The workflow definition contains a cycle (e.g., Node A → B → A), which "
                    + "violates the DAG (Directed Acyclic Graph) requirement.",
                "Review your edges configuration and ensure no node can be reached from itself "
                    + "by following the edges forward. Use acyclic topological ordering."),
            new ErrorExample(
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

  @Override
  public Mono<SessionCreationResult> createSession(final String sessionConfigJson) {
    return parseSessionConfig(sessionConfigJson)
        .flatMap(
            config ->
                sessionService
                    .applyConfig(config)
                    .then(
                        Mono.fromCallable(
                            () -> {
                              final List<String> createdWorkflows =
                                  new ArrayList<>(config.workflows().keySet());
                              return new SessionCreationResult(
                                  config.sessionId(), createdWorkflows, List.of(), true);
                            })))
        .onErrorResume(
            e -> {
              log.atWarn().setCause(e).log("Failed to create session: {}", e.getMessage());
              return Mono.error(
                  new IllegalArgumentException(
                      "Failed to create session: "
                          + e.getMessage()
                          + ". Use get_session_creation_instructions for the expected "
                          + "configuration format.",
                      e));
            });
  }

  private Mono<SessionConfigData> parseSessionConfig(final String sessionConfigJson) {
    return Mono.fromCallable(
            () -> objectMapper.readValue(sessionConfigJson, SessionConfigData.class))
        .onErrorResume(
            e -> {
              log.atWarn()
                  .setCause(e)
                  .log("Failed to parse session configuration JSON: {}", e.getMessage());
              return Mono.error(
                  new IllegalArgumentException(
                      "Invalid JSON format or missing required fields: " + e.getMessage(), e));
            });
  }
}
