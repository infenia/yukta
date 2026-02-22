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
package com.infenia.jagratha.service;

import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.plugin.TerminalPlugin;
import com.infenia.jagratha.plugin.TriggerPlugin;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import com.infenia.jagratha.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods"})
public class WorkflowOrchestrator {

  private final WorkflowRegistry registry;
  private final TaskTrackerService tracker;

  /**
   * Validate and initialize all plugins in the workflow.
   *
   * @param def the workflow definition
   * @return a Mono that completes if preparation is successful
   */
  public Mono<Void> prepareWorkflow(@NotNull @Valid final WorkflowDefinition def) {
    return validateStructuralIntegrity(def)
        .thenMany(Flux.fromIterable(def.nodes()))
        .flatMap(
            node -> {
              final WorkflowPlugin plugin = registry.get(node.type());
              if (plugin == null) {
                return Mono.error(
                    new IllegalArgumentException("Plugin not found for type: " + node.type()));
              }
              return plugin.validateConfig(node.config()).then(plugin.initialize(node.config()));
            })
        .then();
  }

  /**
   * Execute the workflow.
   *
   * @param sessionId the session identifier
   * @param def the workflow definition
   * @param payload the initial trigger payload
   * @return a Mono that completes when all branches of the workflow have finished
   */
  public Mono<Void> execute(
      @SessionId final String sessionId,
      @NotNull @Valid final WorkflowDefinition def,
      @NotEmpty final Map<String, Object> payload) {
    final List<Node> triggers =
        def.nodes().stream()
            .filter(n -> registry.get(n.type()).getCategory() == PluginCategory.TRIGGER)
            .collect(Collectors.toList());

    final List<String> nodeIds =
        def.nodes().stream().map(Node::nodeId).collect(Collectors.toList());
    tracker.startWorkflow(sessionId, nodeIds);

    return Flux.fromIterable(triggers)
        .flatMap(
            triggerNode -> {
              final TriggerPlugin trigger = (TriggerPlugin) registry.get(triggerNode.type());
              tracker.updateTaskStatus(sessionId, triggerNode.nodeId(), "", "RUNNING");
              final Flux<Message> stream =
                  trigger
                      .start(triggerNode.config(), payload)
                      .doOnComplete(
                          () ->
                              tracker.updateTaskStatus(
                                  sessionId, triggerNode.nodeId(), "", "SUCCESS"))
                      .doOnError(
                          e ->
                              tracker.updateTaskStatus(
                                  sessionId, triggerNode.nodeId(), "", "FAILURE"));

              return chain(sessionId, stream, triggerNode, def);
            })
        .then()
        .doOnTerminate(() -> tracker.finishWorkflow(sessionId, "COMPLETED"));
  }

  private Mono<Void> chain(
      final String sessionId,
      final Flux<Message> stream,
      final Node currentNode,
      final WorkflowDefinition def) {
    final List<Node> children = getChildrenOf(currentNode.nodeId(), def);

    if (children.isEmpty()) {
      return Mono.empty();
    }

    // Log messages from current node to console
    final Flux<Message> loggedStream =
        stream.doOnNext(msg -> tracker.appendLog(sessionId, String.valueOf(msg.payload())));

    // Use publish().autoConnect(n) to broadcast the stream if there are multiple children
    final Flux<Message> broadcastStream = loggedStream.publish().autoConnect(children.size());

    return Flux.fromIterable(children)
        .flatMap(
            child -> {
              final WorkflowPlugin plugin = registry.get(child.type());
              tracker.updateTaskStatus(sessionId, child.nodeId(), "", "RUNNING");

              if (plugin instanceof ProcessorPlugin processor) {
                final Flux<Message> processedStream =
                    processor
                        .process(broadcastStream, child.config())
                        .doOnComplete(
                            () ->
                                tracker.updateTaskStatus(sessionId, child.nodeId(), "", "SUCCESS"))
                        .doOnError(
                            e ->
                                tracker.updateTaskStatus(sessionId, child.nodeId(), "", "FAILURE"));
                return chain(sessionId, processedStream, child, def);
              } else if (plugin instanceof TerminalPlugin terminal) {
                return terminal
                    .consume(broadcastStream, child.config())
                    .doOnSuccess(
                        v -> tracker.updateTaskStatus(sessionId, child.nodeId(), "", "SUCCESS"))
                    .doOnError(
                        e -> tracker.updateTaskStatus(sessionId, child.nodeId(), "", "FAILURE"));
              }
              return Mono.empty();
            })
        .then();
  }

  private List<Node> getChildrenOf(final String nodeId, final WorkflowDefinition def) {
    final Set<String> childrenIds =
        def.edges().stream()
            .filter(e -> e.source().equals(nodeId))
            .map(WorkflowDefinition.Edge::target)
            .collect(Collectors.toSet());

    return def.nodes().stream()
        .filter(n -> childrenIds.contains(n.nodeId()))
        .collect(Collectors.toList());
  }

  private Mono<Void> validateStructuralIntegrity(final WorkflowDefinition def) {
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());

    return validateEntryPoints(def, targetIds)
        .then(validateProcessors(def, targetIds, sourceIds))
        .then(validateEndpoints(def, sourceIds))
        .then(validateNoCycles(def));
  }

  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private Mono<Void> validateEntryPoints(
      final WorkflowDefinition def, final Set<String> targetIds) {
    for (final Node node : def.nodes()) {
      if (!targetIds.contains(node.nodeId())) {
        final WorkflowPlugin plugin = registry.get(node.type());
        if (plugin != null && plugin.getCategory() != PluginCategory.TRIGGER) {
          return Mono.error(
              new IllegalArgumentException(
                  "Node " + node.nodeId() + " is an entry point but not a TRIGGER"));
        }
      }
    }
    return Mono.empty();
  }

  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private Mono<Void> validateProcessors(
      final WorkflowDefinition def, final Set<String> targetIds, final Set<String> sourceIds) {
    for (final Node node : def.nodes()) {
      final WorkflowPlugin plugin = registry.get(node.type());
      if (plugin != null
          && plugin.getCategory() == PluginCategory.PROCESSOR
          && (!targetIds.contains(node.nodeId()) || !sourceIds.contains(node.nodeId()))) {
        return Mono.error(
            new IllegalArgumentException(
                "Processor node " + node.nodeId() + " must have both incoming and outgoing edges"));
      }
    }
    return Mono.empty();
  }

  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private Mono<Void> validateEndpoints(final WorkflowDefinition def, final Set<String> sourceIds) {
    for (final Node node : def.nodes()) {
      if (!sourceIds.contains(node.nodeId())) {
        final WorkflowPlugin plugin = registry.get(node.type());
        if (plugin != null && plugin.getCategory() != PluginCategory.TERMINAL) {
          return Mono.error(
              new IllegalArgumentException(
                  "Node " + node.nodeId() + " is an endpoint but not a TERMINAL"));
        }
      }
    }
    return Mono.empty();
  }

  private Mono<Void> validateNoCycles(final WorkflowDefinition def) {
    if (hasCycles(def)) {
      return Mono.error(new IllegalArgumentException("Workflow DAG contains cycles"));
    }
    return Mono.empty();
  }

  private boolean hasCycles(final WorkflowDefinition def) {
    final Map<String, List<String>> adjacencyList = new ConcurrentHashMap<>();
    def.nodes().forEach(n -> adjacencyList.put(n.nodeId(), new ArrayList<>()));
    def.edges().forEach(e -> adjacencyList.get(e.source()).add(e.target()));

    final Set<String> visited = new HashSet<>();
    final Set<String> recStack = new HashSet<>();

    for (final String nodeId : adjacencyList.keySet()) {
      if (isCyclicUtil(nodeId, visited, recStack, adjacencyList)) {
        return true;
      }
    }
    return false;
  }

  private boolean isCyclicUtil(
      final String nodeId,
      final Set<String> visited,
      final Set<String> recStack,
      final Map<String, List<String>> adj) {
    if (recStack.contains(nodeId)) {
      return true;
    }
    if (visited.contains(nodeId)) {
      return false;
    }

    visited.add(nodeId);
    recStack.add(nodeId);

    final List<String> children = adj.get(nodeId);
    for (final String child : children) {
      if (isCyclicUtil(child, visited, recStack, adj)) {
        return true;
      }
    }

    recStack.remove(nodeId);
    return false;
  }
}
