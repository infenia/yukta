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
import java.time.Duration;
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
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods"})
public class WorkflowOrchestrator {

  private static final int SINGLE_CHILD = 1;

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
        .then(
            Flux.fromIterable(def.nodes())
                .flatMap(
                    node -> {
                      final WorkflowPlugin plugin = registry.get(node.type());
                      if (plugin == null) {
                        return Mono.error(
                            new IllegalArgumentException(
                                "Plugin not found for type: " + node.type()));
                      }
                      return plugin
                          .validateConfig(node.config())
                          .then(plugin.initialize(node.config()));
                    })
                .then())
        .onErrorResume(
            e ->
                Flux.fromIterable(def.nodes())
                    .flatMap(
                        node -> {
                          final WorkflowPlugin plugin = registry.get(node.type());
                          if (plugin != null) {
                            final Mono<Void> shutdown = plugin.shutdown(node.config());
                            return (shutdown != null ? shutdown : Mono.<Void>empty())
                                .onErrorResume(ex -> Mono.empty());
                          }
                          return Mono.empty();
                        })
                    .then(Mono.error(e)));
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

    final List<String> nodeIds = def.nodes().stream().map(Node::nodeId).toList();
    final Map<String, List<String>> parentsMap = new ConcurrentHashMap<>();
    final Map<String, List<String>> childrenMap = new ConcurrentHashMap<>();

    def.nodes()
        .forEach(
            n -> {
              parentsMap.put(n.nodeId(), new ArrayList<>());
              childrenMap.put(n.nodeId(), new ArrayList<>());
            });

    def.edges()
        .forEach(
            e -> {
              childrenMap.get(e.source()).add(e.target());
              parentsMap.get(e.target()).add(e.source());
            });

    return Mono.deferContextual(
            ctx -> {
              final String sId = ctx.get("sessionId");
              tracker.startWorkflow(sId, nodeIds);

              final Map<String, Flux<Message>> nodeStreams = new ConcurrentHashMap<>();
              final Map<String, Mono<Void>> completions = new ConcurrentHashMap<>();

              def.nodes()
                  .forEach(
                      node ->
                          buildNode(
                              sId,
                              node,
                              def,
                              payload,
                              parentsMap,
                              childrenMap,
                              nodeStreams,
                              completions));

              final List<Mono<Void>> terminals =
                  def.nodes().stream()
                      .filter(n -> registry.get(n.type()).getCategory() == PluginCategory.TERMINAL)
                      .map(n -> completions.get(n.nodeId()))
                      .toList();

              return Flux.fromIterable(terminals)
                  .flatMapDelayError(m -> m, 256, 32)
                  .then()
                  .doOnTerminate(() -> tracker.finishWorkflow(sId, "COMPLETED"));
            })
        .contextWrite(Context.of("sessionId", sessionId));
  }

  @SuppressWarnings("unchecked")
  private void buildNode(
      final String sessionId,
      final Node node,
      final WorkflowDefinition def,
      final Map<String, Object> payload,
      final Map<String, List<String>> parentsMap,
      final Map<String, List<String>> childrenMap,
      final Map<String, Flux<Message>> nodeStreams,
      final Map<String, Mono<Void>> completions) {

    if (nodeStreams.containsKey(node.nodeId()) || completions.containsKey(node.nodeId())) {
      return;
    }

    final List<String> parentIds = parentsMap.get(node.nodeId());
    final List<String> childrenIds = childrenMap.get(node.nodeId());
    final WorkflowPlugin plugin = registry.get(node.type());

    if (plugin.getCategory() == PluginCategory.TRIGGER) {
      final TriggerPlugin trigger = (TriggerPlugin) plugin;
      final Flux<Message> stream =
          trigger
              .start(node.config(), payload)
              .doOnSubscribe(s -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "RUNNING"))
              .doOnComplete(() -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "SUCCESS"))
              .doOnError(e -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "FAILURE"));

      applyLoggingAndBroadcasting(
          sessionId, node.nodeId(), stream, childrenIds.size(), nodeStreams);
    } else {
      // Ensure parents are built
      parentIds.forEach(
          parentId -> {
            final Node parentNode =
                def.nodes().stream()
                    .filter(n -> n.nodeId().equals(parentId))
                    .findFirst()
                    .orElseThrow();
            buildNode(
                sessionId,
                parentNode,
                def,
                payload,
                parentsMap,
                childrenMap,
                nodeStreams,
                completions);
          });

      final Flux<Message> mergedInput =
          Flux.merge(parentIds.stream().map(nodeStreams::get).toList());

      if (plugin instanceof ProcessorPlugin processor) {
        final Flux<Message> stream =
            processor
                .process(mergedInput, node.config())
                .doOnSubscribe(
                    s -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "RUNNING"))
                .doOnComplete(
                    () -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "SUCCESS"))
                .doOnError(e -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "FAILURE"));

        applyLoggingAndBroadcasting(
            sessionId, node.nodeId(), stream, childrenIds.size(), nodeStreams);
      } else if (plugin instanceof TerminalPlugin terminal) {
        final Mono<Void> completion =
            terminal
                .consume(mergedInput, node.config())
                .doOnSubscribe(
                    s -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "RUNNING"))
                .doOnSuccess(v -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "SUCCESS"))
                .doOnError(e -> tracker.updateTaskStatus(sessionId, node.nodeId(), "", "FAILURE"));
        completions.put(node.nodeId(), completion);
      }
    }
  }

  private Flux<Message> applyLoggingAndBroadcasting(
      final String sessionId,
      final String nodeId,
      final Flux<Message> stream,
      final int childCount,
      final Map<String, Flux<Message>> nodeStreams) {

    Flux<Message> processedStream =
        stream
            .onBackpressureBuffer()
            .doOnNext(msg -> tracker.appendLog(sessionId, String.valueOf(msg.payload())));

    if (childCount > SINGLE_CHILD) {
      processedStream =
          processedStream.publish().autoConnect(childCount).timeout(Duration.ofSeconds(30));
    } else {
      // For single child or no children (though validations prevent 0 for triggers/processors),
      // we still use autoConnect(1) for consistency in non-terminal nodes if needed,
      // but actually for 1 child we don't strictly need it.
      // However, to keep it simple and safe for all non-terminal nodes:
      processedStream = processedStream.publish().autoConnect(1).timeout(Duration.ofSeconds(30));
    }

    nodeStreams.put(nodeId, processedStream);
    return processedStream;
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
      final WorkflowPlugin plugin = registry.get(node.type());
      if (plugin == null) {
        continue;
      }
      final boolean isEntryPoint = !targetIds.contains(node.nodeId());
      final boolean isTrigger = plugin.getCategory() == PluginCategory.TRIGGER;

      if (isEntryPoint) {
        if (!isTrigger) {
          return Mono.error(
              new IllegalArgumentException(
                  "Node " + node.nodeId() + " is an entry point but not a TRIGGER"));
        }
      } else if (isTrigger) {
        return Mono.error(
            new IllegalArgumentException(
                "Trigger node " + node.nodeId() + " cannot have incoming edges"));
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
      final WorkflowPlugin plugin = registry.get(node.type());
      if (plugin == null) {
        continue;
      }
      final boolean isEndpoint = !sourceIds.contains(node.nodeId());
      final boolean isTerminal = plugin.getCategory() == PluginCategory.TERMINAL;

      if (isEndpoint) {
        if (!isTerminal) {
          return Mono.error(
              new IllegalArgumentException(
                  "Node " + node.nodeId() + " is an endpoint but not a TERMINAL"));
        }
      } else if (isTerminal) {
        return Mono.error(
            new IllegalArgumentException(
                "Terminal node " + node.nodeId() + " cannot have outgoing edges"));
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
