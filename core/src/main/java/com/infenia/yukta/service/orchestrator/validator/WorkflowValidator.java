// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import jakarta.validation.Valid;
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

/** Service for validating workflow structural integrity and plugin configurations. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.TooManyMethods"
})
public class WorkflowValidator {

  /** The plugin registry for resolving plugin types. */
  private final PluginRegistry registry;

  /**
   * Validate the structural integrity of a workflow definition.
   *
   * @param def the workflow definition
   * @return a Mono that completes if validation is successful
   */
  public Mono<Void> validate(@NotNull @Valid final WorkflowDefinition def) {
    log.atDebug()
        .addKeyValue("workflowId", def.workflowId())
        .addKeyValue("nodeCount", def.nodes().size())
        .addKeyValue("edgeCount", def.edges().size())
        .log("Starting workflow validation");

    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());

    return validatePluginsRegistered(def)
        .then(validateTriggerNodeExists(def))
        .then(validateTerminalNodeExists(def))
        .then(validateEntryPoints(def, targetIds))
        .then(validateProcessors(def, targetIds, sourceIds))
        .then(validateEndpoints(def, sourceIds))
        .then(validateNoCycles(def))
        .then(validateNoOrphans())
        .then(validateNodeContexts(def))
        .then(validatePluginConfigs(def))
        .doOnSuccess(
            _ ->
                log.atInfo()
                    .addKeyValue("workflowId", def.workflowId())
                    .log("Workflow validation completed successfully"))
        .doOnError(
            e ->
                log.atError()
                    .setCause(e)
                    .addKeyValue("workflowId", def.workflowId())
                    .log("Workflow validation failed"));
  }

  private Mono<Void> validatePluginsRegistered(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              if (plugin == null) {
                log.atWarn()
                    .addKeyValue("nodeId", node.nodeId())
                    .addKeyValue("pluginType", node.type())
                    .log("Plugin not registered for node type");
                return Mono.error(
                    new IllegalArgumentException("Plugin not found for type: " + node.type()));
              }
              log.atDebug()
                  .addKeyValue("nodeId", node.nodeId())
                  .addKeyValue("pluginType", node.type())
                  .log("Plugin found and registered");
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateTriggerNodeExists(final WorkflowDefinition def) {
    final boolean hasTrigger =
        def.nodes().stream()
            .anyMatch(
                node -> {
                  final Plugin plugin = registry.get(node.type());
                  return plugin instanceof TriggerPlugin;
                });

    if (!hasTrigger) {
      log.atError()
          .addKeyValue("workflowId", def.workflowId())
          .log("Workflow has no TRIGGER nodes");
      return Mono.error(
          new IllegalArgumentException("Workflow must contain at least one TRIGGER node"));
    }
    log.atDebug()
        .addKeyValue("workflowId", def.workflowId())
        .log("Workflow contains at least one TRIGGER node");
    return Mono.empty();
  }

  private Mono<Void> validateTerminalNodeExists(final WorkflowDefinition def) {
    final boolean hasTerminal =
        def.nodes().stream()
            .anyMatch(
                node -> {
                  final Plugin plugin = registry.get(node.type());
                  return plugin != null && plugin.getCategory() == PluginCategory.TERMINAL;
                });

    if (!hasTerminal) {
      log.atError()
          .addKeyValue("workflowId", def.workflowId())
          .log("Workflow has no TERMINAL nodes");
      return Mono.error(
          new IllegalArgumentException("Workflow must contain at least one TERMINAL node"));
    }
    log.atDebug()
        .addKeyValue("workflowId", def.workflowId())
        .log("Workflow contains at least one TERMINAL node");
    return Mono.empty();
  }

  private Mono<Void> validateEntryPoints(
      final WorkflowDefinition def, final Set<String> targetIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              final boolean isEntryPoint = !targetIds.contains(node.nodeId());
              final boolean canBeTrigger = plugin instanceof TriggerPlugin;
              final boolean mustBeTrigger = plugin.getCategory() == PluginCategory.TRIGGER;

              if (isEntryPoint && !canBeTrigger) {
                log.atWarn()
                    .addKeyValue("nodeId", node.nodeId())
                    .addKeyValue("pluginType", node.type())
                    .log("Entry point node is not a TRIGGER");
                return Mono.error(
                    new IllegalArgumentException(
                        "Node " + node.nodeId() + " is an entry point but not a TRIGGER"));
              }
              if (!isEntryPoint && mustBeTrigger) {
                log.atWarn()
                    .addKeyValue("nodeId", node.nodeId())
                    .log("TRIGGER node has incoming edges");
                return Mono.error(
                    new IllegalArgumentException(
                        "Trigger node " + node.nodeId() + " cannot have incoming edges"));
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateProcessors(
      final WorkflowDefinition def, final Set<String> targetIds, final Set<String> sourceIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              // Plugin is guaranteed non-null by validatePluginsRegistered (runs first)
              final boolean isProcessor = plugin.getCategory() == PluginCategory.PROCESSOR;
              final boolean isEntryPoint = !targetIds.contains(node.nodeId());
              final boolean hasOutgoing = sourceIds.contains(node.nodeId());

              // Processor nodes must have both incoming and outgoing edges
              // (unless it's an entry-point trigger, which is caught by validateEntryPoints)
              if (isProcessor && !isEntryPoint && !hasOutgoing) {
                log.atWarn()
                    .addKeyValue("nodeId", node.nodeId())
                    .addKeyValue("isEntryPoint", false)
                    .addKeyValue("hasOutgoing", false)
                    .log("PROCESSOR node missing required edges");
                return Mono.error(
                    new IllegalArgumentException(
                        "Processor node "
                            + node.nodeId()
                            + " must have both incoming and outgoing edges"));
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Void> validateEndpoints(final WorkflowDefinition def, final Set<String> sourceIds) {
    return Flux.fromIterable(def.nodes())
        .concatMap(node -> validateEndpointNode(node, sourceIds))
        .then();
  }

  private Mono<Void> validateEndpointNode(
      final WorkflowDefinition.Node node, final Set<String> sourceIds) {
    final Plugin plugin = registry.get(node.type());
    // Plugin is guaranteed non-null by validatePluginsRegistered (runs first)
    final boolean isEndpoint = !sourceIds.contains(node.nodeId());
    final boolean isTerminal = plugin.getCategory() == PluginCategory.TERMINAL;

    if (isEndpoint && !isTerminal) {
      log.atWarn()
          .addKeyValue("nodeId", node.nodeId())
          .addKeyValue("pluginType", node.type())
          .log("Endpoint node is not a TERMINAL");
      return Mono.error(
          new IllegalArgumentException(
              "Node " + node.nodeId() + " is an endpoint but not a TERMINAL"));
    }
    if (!isEndpoint && isTerminal) {
      log.atWarn().addKeyValue("nodeId", node.nodeId()).log("TERMINAL node has outgoing edges");
      return Mono.error(
          new IllegalArgumentException(
              "Terminal node " + node.nodeId() + " cannot have outgoing edges"));
    }
    return Mono.empty();
  }

  private Mono<Void> validateNodeContexts(final WorkflowDefinition def) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              // Plugin is guaranteed non-null by validatePluginsRegistered (runs first)
              final WorkflowContext context = buildContext(node.nodeId(), def);
              return plugin.validateInContext(context, node.config());
            })
        .then();
  }

  private WorkflowContext buildContext(final String nodeId, final WorkflowDefinition def) {
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

  private Mono<Void> validateNoCycles(final WorkflowDefinition def) {
    if (hasCycles(def)) {
      log.atError().addKeyValue("workflowId", def.workflowId()).log("Workflow DAG contains cycles");
      return Mono.error(new IllegalArgumentException("Workflow DAG contains cycles"));
    }
    log.atDebug().addKeyValue("workflowId", def.workflowId()).log("Workflow DAG is acyclic");
    return Mono.empty();
  }

  private Mono<Void> validateNoOrphans() {
    // All nodes are guaranteed to be reachable from at least one trigger due to earlier
    // validations (validateEntryPoints ensures no isolated non-trigger nodes can exist).
    // Therefore, no separate orphan validation is needed.
    return Mono.empty();
  }

  private Mono<Void> validatePluginConfigs(final WorkflowDefinition def) {
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

  private boolean hasCycles(final WorkflowDefinition def) {
    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes().forEach(node -> adj.put(node.nodeId(), new ArrayList<>()));
    def.edges()
        .forEach(
            edge -> {
              // sourceAdj is guaranteed non-null (all nodes initialized above)
              adj.get(edge.source()).add(edge.target());
            });

    final Set<String> visited = new HashSet<>();
    final Set<String> recStack = new HashSet<>();

    for (final String nodeId : adj.keySet()) {
      if (isCyclicUtil(nodeId, visited, recStack, adj)) {
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
    boolean found = false;
    if (recStack.contains(nodeId)) {
      found = true;
    } else if (!visited.contains(nodeId)) {
      visited.add(nodeId);
      recStack.add(nodeId);

      found = checkChildrenForCycles(adj.get(nodeId), visited, recStack, adj);

      recStack.remove(nodeId);
    }
    return found;
  }

  private boolean checkChildrenForCycles(
      final List<String> children,
      final Set<String> visited,
      final Set<String> recStack,
      final Map<String, List<String>> adj) {
    // children is guaranteed non-null (all nodes initialized in hasCycles)
    for (final String child : children) {
      if (isCyclicUtil(child, visited, recStack, adj)) {
        return true;
      }
    }
    return false;
  }
}
