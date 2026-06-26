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
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.dto.response.ErrorExample;
import com.infenia.yukta.dto.response.PluginReference;
import com.infenia.yukta.dto.response.SessionCreationGuide;
import com.infenia.yukta.dto.response.SessionCreationResponse;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionInfo;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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
  @SuppressWarnings("unchecked")
  public Mono<SessionDetails> getSessionDetails(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .map(
            config -> {
              final Map<String, Object> workflows =
                  (Map<String, Object>) config.getOrDefault("workflows", Map.of());
              return new SessionDetails(sessionId, List.copyOf(workflows.keySet()));
            });
  }

  @Override
  @SuppressWarnings("unchecked")
  public Flux<SessionInfo> listSessions() {
    return sessionService
        .getSessionIds()
        .flatMap(
            sessionId ->
                sessionService
                    .getSessionConfig(sessionId)
                    .map(
                        config -> {
                          final Map<String, Object> workflows =
                              (Map<String, Object>) config.getOrDefault("workflows", Map.of());
                          final int workflowCount = workflows.size();
                          final LocalDateTime now = LocalDateTime.now();
                          return new SessionInfo(sessionId, workflowCount, now, now, "active");
                        })
                    .onErrorResume(
                        e -> {
                          log.atWarn()
                              .setCause(e)
                              .log(
                                  "Failed to load session config for {}: {}",
                                  sessionId,
                                  e.getMessage());
                          return Mono.empty();
                        }));
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
                "Check the available plugins using the listPlugins() tool. Ensure the 'type' "
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
                "Review the plugin's usage pattern using getPluginDetails(pluginType) and ensure "
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
  public Mono<SessionCreationResponse> createSession(final String sessionConfigJson) {
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
                              return new SessionCreationResponse(
                                  config.sessionId(), createdWorkflows, List.of(), true);
                            })))
        .onErrorResume(
            e -> {
              log.atWarn().setCause(e).log("Failed to create session: {}", e.getMessage());
              return Mono.just(
                  new SessionCreationResponse(
                      "", List.of(), List.of("Error creating session: " + e.getMessage()), false));
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
