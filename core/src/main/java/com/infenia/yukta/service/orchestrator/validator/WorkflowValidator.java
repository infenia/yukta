// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  "PMD.TooManyMethods",
  "PMD.GodClass"
})
public class WorkflowValidator {

  /** Largest accepted number of nodes. */
  private static final int MAX_NODES = 500;

  /** Largest accepted number of edges. */
  private static final int MAX_EDGES = 2000;

  /** The plugin registry for resolving plugin types. */
  private final PluginRegistry registry;

  /**
   * Finds the first structural problem: graph too large, duplicate node id or edge, an edge to an
   * unknown node, or an edge from an output port the source plugin does not have.
   *
   * <p>Checked eagerly, before the other validations are assembled, because they assume every edge
   * endpoint exists and ids are unique.
   */
  private Optional<String> findStructuralProblem(final WorkflowDefinition def) {
    if (def.nodes().size() > MAX_NODES || def.edges().size() > MAX_EDGES) {
      return Optional.of(
          "Workflow is too large: at most " + MAX_NODES + " nodes and " + MAX_EDGES + " edges");
    }
    final Map<String, WorkflowDefinition.Node> nodes = new ConcurrentHashMap<>();
    for (final WorkflowDefinition.Node node : def.nodes()) {
      if (nodes.put(node.nodeId(), node) != null) {
        return Optional.of("Duplicate node id '" + node.nodeId() + "'");
      }
    }
    final Set<WorkflowDefinition.Edge> seen = new HashSet<>();
    for (final WorkflowDefinition.Edge edge : def.edges()) {
      final Optional<String> problem = findEdgeProblem(edge, nodes, seen);
      if (problem.isPresent()) {
        return problem;
      }
    }
    return Optional.empty();
  }

  private Optional<String> findEdgeProblem(
      final WorkflowDefinition.Edge edge,
      final Map<String, WorkflowDefinition.Node> nodes,
      final Set<WorkflowDefinition.Edge> seen) {
    final String label = "Edge " + edge.source() + " -> " + edge.target();
    if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
      return Optional.of(label + " references an unknown node");
    }
    if (!seen.add(edge)) {
      return Optional.of(label + " is defined more than once");
    }
    if (edge.sourcePort() != null && !hasPort(nodes.get(edge.source()), edge.sourcePort())) {
      return Optional.of(
          label + " uses output port '" + edge.sourcePort() + "' that the source does not have");
    }
    return Optional.empty();
  }

  /** True if the node's plugin declares the port, or it is the error port of a ROUTE policy. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private boolean hasPort(final WorkflowDefinition.Node node, final String port) {
    final Plugin plugin = registry.get(node.type());
    if (plugin == null) {
      return true; // reported by validatePluginsRegistered
    }
    try {
      return plugin.getOutputPorts(node.config()).contains(port)
          || NodePolicy.ERROR_PORT.equals(port)
              && NodePolicy.from(node.config()).onError() == NodePolicy.OnError.ROUTE;
    } catch (RuntimeException e) {
      // Invalid config or policy: findInvalidPolicy / validatePluginConfigs report it cleanly.
      return true;
    }
  }

  /**
   * Finds the first node whose engine policy is malformed or not supported by its plugin category.
   *
   * <p>Checked eagerly, like the edge check, so the message names the node and the option.
   */
  private Optional<String> findInvalidPolicy(final WorkflowDefinition def) {
    for (final WorkflowDefinition.Node node : def.nodes()) {
      final NodePolicy policy;
      try {
        policy = NodePolicy.from(node.config());
      } catch (final IllegalArgumentException e) {
        return Optional.of("Node '" + node.nodeId() + "': " + e.getMessage());
      }
      final Optional<String> backoff = findLongBackoff(node, policy);
      if (backoff.isPresent()) {
        return backoff;
      }
      if (policy.concurrencyGroup() != null && !isProcessor(node)) {
        return Optional.of(
            "Node '"
                + node.nodeId()
                + "': engine.concurrencyGroup is only supported on processor nodes");
      }
      if (policy.perMessage()) {
        final var plugin = registry.get(node.type());
        if (plugin != null && plugin.getCategory() != PluginCategory.PROCESSOR) {
          return Optional.of(
              "Node '"
                  + node.nodeId()
                  + "': engine.retry, engine.onError and engine.elementTimeoutMs are only supported"
                  + " on processor nodes");
        }
      }
    }
    return Optional.empty();
  }

  /** False only for a node whose plugin is known and is not a processor. */
  private boolean isProcessor(final WorkflowDefinition.Node node) {
    final var plugin = registry.get(node.type());
    return plugin == null || plugin.getCategory() == PluginCategory.PROCESSOR;
  }

  /** The node's idle timeout, or null if it is unknown or not positive. */
  private Duration nodeTimeout(final WorkflowDefinition.Node node) {
    final Object configured = node.config().get("timeoutSeconds");
    final Plugin plugin = registry.get(node.type());
    final Duration timeout =
        configured instanceof Number seconds && seconds.longValue() > 0
            ? Duration.ofSeconds(seconds.longValue())
            : plugin == null ? null : plugin.getDefaultTimeout(node.config());
    return timeout != null && timeout.isPositive() ? timeout : null;
  }

  /**
   * A retry delay or element timeout as long as the node timeout makes the node time out first, so
   * the policy could never work as configured.
   */
  private Optional<String> findLongBackoff(
      final WorkflowDefinition.Node node, final NodePolicy policy) {
    final Duration element = policy.elementTimeout();
    final Duration deadline = policy.deadline();
    if (deadline != null && element != null && element.compareTo(deadline) >= 0) {
      return Optional.of(
          "Node '"
              + node.nodeId()
              + "': engine.elementTimeoutMs must be below engine.deadlineMs ("
              + deadline.toMillis()
              + " ms), which would end the node first");
    }
    final Duration timeout = nodeTimeout(node);
    if (timeout == null) {
      return Optional.empty();
    }
    final NodePolicy.Retry retry = policy.retry();
    if (retry != null && retry.maxBackoff().compareTo(timeout) >= 0) {
      return Optional.of(
          tooLong(
              node, "engine.retry.maxBackoffMs", timeout, "or the node times out while retrying"));
    }
    if (element != null && element.compareTo(timeout) >= 0) {
      return Optional.of(
          tooLong(node, "engine.elementTimeoutMs", timeout, "which would end the node first"));
    }
    return Optional.empty();
  }

  private static String tooLong(
      final WorkflowDefinition.Node node,
      final String option,
      final Duration timeout,
      final String consequence) {
    return "Node '"
        + node.nodeId()
        + "': "
        + option
        + " must be below the node timeout ("
        + timeout.toSeconds()
        + " s), "
        + consequence;
  }

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

    final List<String> masked = ConfigRedactor.placeholders(def);
    if (!masked.isEmpty()) {
      return Mono.error(
          new IllegalArgumentException(
              "The definition holds the secret mask '"
                  + ConfigRedactor.PLACEHOLDER
                  + "' at "
                  + String.join(", ", masked)
                  + ". The read APIs mask literal secrets; replace each with the real value or a"
                  + " decrypted:<key> reference before saving, or the stored secret would be"
                  + " lost"));
    }

    final List<String> literals = new ConfigRedactor(registry).literalSecrets(def);
    if (!literals.isEmpty()) {
      return Mono.error(
          new IllegalArgumentException(
              "A workflow definition must not hold secrets, but "
                  + String.join(", ", literals)
                  + " is a literal value. Store the secret as YUKTA_SECRET_<KEY> and write"
                  + " decrypted:<key> (or a ${...} variable) here instead"));
    }

    final Optional<String> invalidPolicy = findInvalidPolicy(def);
    if (invalidPolicy.isPresent()) {
      return Mono.error(new IllegalArgumentException(invalidPolicy.get()));
    }

    final Optional<String> structural = findStructuralProblem(def);
    if (structural.isPresent()) {
      return Mono.error(new IllegalArgumentException(structural.get()));
    }

    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());

    return validatePluginsRegistered(def)
        .then(validateTriggerNodeExists(def))
        .then(validateTerminalNodeExists(def))
        .then(validateEntryPoints(def, targetIds))
        .then(validateProcessors(def, sourceIds))
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

  /**
   * A terminal must declare the category and be a {@link TerminalPlugin}, because the assembler
   * only builds a terminal from the type; a plugin that has only one of them is not accepted.
   */
  @SuppressWarnings("PMD.LawOfDemeter")
  private static boolean isTerminal(final Plugin plugin) {
    return plugin != null
        && plugin.getCategory() == PluginCategory.TERMINAL
        && plugin instanceof TerminalPlugin;
  }

  private Mono<Void> validateTerminalNodeExists(final WorkflowDefinition def) {
    final boolean hasTerminal =
        def.nodes().stream()
            .anyMatch(
                node -> {
                  final Plugin plugin = registry.get(node.type());
                  return isTerminal(plugin);
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

  private Mono<Void> validateProcessors(final WorkflowDefinition def, final Set<String> sourceIds) {
    return Flux.fromIterable(def.nodes())
        .flatMap(
            node -> {
              final Plugin plugin = registry.get(node.type());
              // Plugin is guaranteed non-null by validatePluginsRegistered (runs first)
              final boolean isProcessor = plugin.getCategory() == PluginCategory.PROCESSOR;
              final boolean hasOutgoing = sourceIds.contains(node.nodeId());

              // A processor without incoming edges is already rejected by validateEntryPoints, so
              // only the outgoing side is left to check here.
              if (isProcessor && !hasOutgoing) {
                log.atWarn()
                    .addKeyValue("nodeId", node.nodeId())
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
    final boolean isTerminal = isTerminal(plugin);

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

  /** Kahn's algorithm: the graph has a cycle if not every node can be taken off in order. */
  private boolean hasCycles(final WorkflowDefinition def) {
    final Map<String, Integer> inDegree = new ConcurrentHashMap<>();
    final Map<String, List<String>> adj = new ConcurrentHashMap<>();
    def.nodes()
        .forEach(
            node -> {
              inDegree.put(node.nodeId(), 0);
              adj.put(node.nodeId(), new ArrayList<>());
            });
    def.edges()
        .forEach(
            edge -> {
              adj.get(edge.source()).add(edge.target());
              inDegree.merge(edge.target(), 1, Integer::sum);
            });
    final Deque<String> ready = new ArrayDeque<>();
    inDegree.forEach((nodeId, degree) -> addIfZero(ready, nodeId, degree));
    int removed = 0;
    while (!ready.isEmpty()) {
      removed++;
      for (final String child : adj.get(ready.poll())) {
        addIfZero(ready, child, inDegree.merge(child, -1, Integer::sum));
      }
    }
    return removed != inDegree.size();
  }

  private static void addIfZero(final Deque<String> ready, final String nodeId, final int degree) {
    if (degree == 0) {
      ready.add(nodeId);
    }
  }
}
