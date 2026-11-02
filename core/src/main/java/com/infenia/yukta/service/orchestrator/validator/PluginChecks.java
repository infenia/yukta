// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The checks that call the plugins and so are genuinely asynchronous: {@code validateInContext} and
 * {@code validateConfig} of every node. They assume every node has a registered plugin.
 */
@Slf4j
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
final class PluginChecks {

  private PluginChecks() {}

  /* default */ static Mono<Void> validateNodeContexts(
      final WorkflowDefinition def, final PluginRegistry registry) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node ->
                registry
                    .get(node.type())
                    .validateInContext(buildContext(node.nodeId(), def), node.config()))
        .then();
  }

  private static WorkflowContext buildContext(final String nodeId, final WorkflowDefinition def) {
    final List<WorkflowEdge> outgoing = new ArrayList<>();
    final List<WorkflowEdge> incoming = new ArrayList<>();

    for (final WorkflowDefinition.Edge edge : def.edges()) {
      final var workflowEdge = new WorkflowEdge(edge.source(), edge.target(), edge.sourcePort());
      if (edge.source().equals(nodeId)) {
        outgoing.add(workflowEdge);
      }
      if (edge.target().equals(nodeId)) {
        incoming.add(workflowEdge);
      }
    }

    return new WorkflowContext(nodeId, outgoing, incoming);
  }

  /* default */ static Mono<Void> validatePluginConfigs(
      final WorkflowDefinition def, final PluginRegistry registry) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              log.atDebug()
                  .addKeyValue("nodeId", node.nodeId())
                  .addKeyValue("pluginType", node.type())
                  .log("Validating plugin configuration");
              return plugin
                  .validateConfig(node.config())
                  .doOnError(
                      e ->
                          log.atWarn()
                              .setCause(e)
                              .addKeyValue("nodeId", node.nodeId())
                              .addKeyValue("pluginType", node.type())
                              .log("Plugin configuration validation failed"));
            })
        .then()
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("workflowId", def.workflowId())
                    .log("All plugin configurations validated successfully"));
  }
}
