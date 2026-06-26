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
package com.infenia.yukta.service.orchestrator.preparator;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.orchestrator.compiler.WorkflowCompiler;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Prepares workflow definitions for execution by validating and initializing plugins. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowPreparator {

  /** Log key for number of nodes. */
  private static final String LOG_KEY_NUM_NODES = "numNodes";

  /** Log key for node IDs list. */
  private static final String LOG_KEY_NODE_IDS = "nodeIds";

  /** Log key for individual node ID. */
  private static final String LOG_KEY_NODE_ID = "nodeId";

  /** Log key for plugin type. */
  private static final String LOG_KEY_PLUGIN_TYPE = "pluginType";

  /** Log key for edge source node. */
  private static final String LOG_KEY_SOURCE = "source";

  /** Log key for edge target node. */
  private static final String LOG_KEY_TARGET = "target";

  /** Log key for edge port. */
  private static final String LOG_KEY_PORT = "port";

  /** Log key for plugin count. */
  private static final String LOG_KEY_PLUGIN_COUNT = "pluginCount";

  /** Registry for looking up plugins by type. */
  private final PluginRegistry registry;

  /** Validator for workflow definitions. */
  private final WorkflowValidator validator;

  /** Service for computing topological order of workflow nodes. */
  private final TopologicalSortService topologicalSortService;

  /** Compiler for workflow templates. */
  private final WorkflowCompiler compiler;

  /**
   * Prepares a workflow for execution.
   *
   * @param def the workflow definition (includes workflowId)
   * @return a Mono containing the prepared workflow
   */
  public Mono<PreparedWorkflow> prepareWorkflow(@NotNull @Valid final WorkflowDefinition def) {
    final int numNodes = def.nodes().size();
    log.atDebug()
        .addKeyValue(LOG_KEY_NUM_NODES, numNodes)
        .addKeyValue(LOG_KEY_NODE_IDS, def.nodes().stream().map(Node::nodeId).toList())
        .log("Preparing workflow with {} nodes", numNodes);

    final Map<String, List<WorkflowNode>> adjacencyList = new ConcurrentHashMap<>(numNodes);
    final Map<String, List<WorkflowNode>> parentsList = new ConcurrentHashMap<>(numNodes);
    final Map<String, Plugin> pluginCache = new ConcurrentHashMap<>(numNodes);
    final Map<String, Node> nodeMap = new ConcurrentHashMap<>(numNodes);

    def.nodes()
        .forEach(
            node -> {
              nodeMap.put(node.nodeId(), node);
              adjacencyList.put(node.nodeId(), new ArrayList<>());
              parentsList.put(node.nodeId(), new ArrayList<>());
              final Plugin plugin = registry.get(node.type());
              if (plugin != null) {
                pluginCache.put(node.nodeId(), plugin);
                log.atTrace()
                    .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                    .addKeyValue(LOG_KEY_PLUGIN_TYPE, node.type())
                    .log("Cached plugin for node");
              } else {
                log.atWarn()
                    .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                    .addKeyValue(LOG_KEY_PLUGIN_TYPE, node.type())
                    .log("Plugin not found for node type");
              }
            });

    def.edges()
        .forEach(
            edge -> {
              final Node targetApiNode = nodeMap.get(edge.target());
              final Node sourceApiNode = nodeMap.get(edge.source());
              final WorkflowNode targetNode =
                  new WorkflowNode(
                      targetApiNode.nodeId(), targetApiNode.type(), targetApiNode.config());
              final WorkflowNode sourceNode =
                  new WorkflowNode(
                      sourceApiNode.nodeId(), sourceApiNode.type(), sourceApiNode.config());
              adjacencyList.get(edge.source()).add(targetNode);
              parentsList.get(edge.target()).add(sourceNode);
              log.atTrace()
                  .addKeyValue(LOG_KEY_SOURCE, edge.source())
                  .addKeyValue(LOG_KEY_TARGET, edge.target())
                  .addKeyValue(LOG_KEY_PORT, edge.sourcePort())
                  .log("Added workflow edge");
            });

    return validator
        .validate(def)
        .doOnError(e -> log.atError().setCause(e).log("Workflow validation failed"))
        .then(
            Flux.fromIterable(def.nodes())
                .flatMap(
                    node -> {
                      final Plugin plugin = pluginCache.get(node.nodeId());
                      if (plugin == null) {
                        log.atError()
                            .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                            .addKeyValue(LOG_KEY_PLUGIN_TYPE, node.type())
                            .log("Plugin not found during initialization");
                        return Mono.error(
                            new IllegalArgumentException("Plugin not found: " + node.type()));
                      }
                      return plugin
                          .initialize(node.config())
                          .doOnSuccess(
                              _ ->
                                  log.atDebug()
                                      .addKeyValue(LOG_KEY_NODE_ID, node.nodeId())
                                      .addKeyValue(LOG_KEY_PLUGIN_TYPE, node.type())
                                      .log("Plugin initialized successfully"))
                          .then(Mono.empty());
                    })
                .then())
        .then(
            Mono.fromCallable(
                () -> {
                  final List<WorkflowNode> workflowNodes =
                      def.nodes().stream()
                          .map(n -> new WorkflowNode(n.nodeId(), n.type(), n.config()))
                          .toList();
                  final List<WorkflowNode> topologicalOrder =
                      topologicalSortService.computeTopologicalOrder(
                          workflowNodes, adjacencyList, parentsList);
                  final List<WorkflowEdge> edges =
                      def.edges().stream()
                          .map(e -> new WorkflowEdge(e.source(), e.target(), e.sourcePort()))
                          .toList();
                  return new PreparedWorkflow(
                      edges,
                      adjacencyList,
                      parentsList,
                      pluginCache,
                      topologicalOrder,
                      compiler.compileTemplate(edges, parentsList, pluginCache, topologicalOrder));
                }))
        .onErrorResume(
            e -> {
              log.atError()
                  .setCause(e)
                  .addKeyValue(LOG_KEY_PLUGIN_COUNT, pluginCache.size())
                  .log("Workflow preparation failed, shutting down plugins");
              return Flux.fromIterable(pluginCache.entrySet())
                  .flatMap(
                      entry -> {
                        final String nodeId = entry.getKey();
                        final Plugin plugin = entry.getValue();

                        log.atDebug()
                            .addKeyValue(LOG_KEY_NODE_ID, nodeId)
                            .log("Unregistering and shutting down plugin");

                        final Node node = nodeMap.get(nodeId);
                        final Mono<Void> shutdown = plugin.shutdown(node.config());

                        return (shutdown != null ? shutdown : Mono.<Void>empty())
                            .onErrorResume(
                                ex -> {
                                  log.atWarn()
                                      .setCause(ex)
                                      .addKeyValue(LOG_KEY_NODE_ID, nodeId)
                                      .log("Error during plugin shutdown");
                                  return Mono.empty();
                                });
                      })
                  .then(Mono.error(e));
            });
  }
}
