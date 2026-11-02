// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The eager checks of a workflow definition: its structure, and the engine policy of each node.
 * They run before the graph checks, which assume that the structure is sound.
 */
final class StructuralRules {

  /** Largest accepted number of nodes. */
  private static final int MAX_NODES = 500;

  /** Largest accepted number of edges. */
  private static final int MAX_EDGES = 2000;

  private StructuralRules() {}

  /**
   * Finds the first structural problem: graph too large, duplicate node id or edge, an edge to an
   * unknown node, or an edge from an output port the source plugin does not have.
   *
   * <p>Checked eagerly, before the other validations are assembled, because they assume every edge
   * endpoint exists and ids are unique.
   */
  /* default */ static Optional<String> findStructuralProblem(
      final WorkflowDefinition def, final PluginRegistry registry) {
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
      final Optional<String> problem = findEdgeProblem(edge, nodes, seen, registry);
      if (problem.isPresent()) {
        return problem;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> findEdgeProblem(
      final WorkflowDefinition.Edge edge,
      final Map<String, WorkflowDefinition.Node> nodes,
      final Set<WorkflowDefinition.Edge> seen,
      final PluginRegistry registry) {
    final String label = "Edge " + edge.source() + " -> " + edge.target();
    if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
      return Optional.of(label + " references an unknown node");
    }
    if (!seen.add(edge)) {
      return Optional.of(label + " is defined more than once");
    }
    if (edge.sourcePort() != null
        && !NodePolicy.ALL_PORTS.equals(edge.sourcePort())
        && !hasPort(nodes.get(edge.source()), edge.sourcePort(), registry)) {
      return Optional.of(
          label + " uses output port '" + edge.sourcePort() + "' that the source does not have");
    }
    return Optional.empty();
  }

  /** True if the node's plugin declares the port, or it is the error port of a ROUTE policy. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static boolean hasPort(
      final WorkflowDefinition.Node node, final String port, final PluginRegistry registry) {
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
  /* default */ static Optional<String> findInvalidPolicy(
      final WorkflowDefinition def, final PluginRegistry registry) {
    for (final WorkflowDefinition.Node node : def.nodes()) {
      final NodePolicy policy;
      try {
        policy = NodePolicy.from(node.config());
      } catch (final IllegalArgumentException e) {
        return Optional.of(nodeProblem(node, e.getMessage()));
      }
      final Optional<String> backoff = findLongBackoff(node, policy, registry);
      if (backoff.isPresent()) {
        return backoff;
      }
      if (policy.concurrencyGroup() != null && !isProcessor(node, registry)) {
        return Optional.of(
            nodeProblem(node, "engine.concurrencyGroup is only supported on processor nodes"));
      }
      if (policy.perMessage()) {
        final var plugin = registry.get(node.type());
        if (plugin != null && plugin.getCategory() != PluginCategory.PROCESSOR) {
          return Optional.of(
              nodeProblem(
                  node,
                  "engine.retry, engine.onError and engine.elementTimeoutMs are only supported"
                      + " on processor nodes"));
        }
      }
    }
    return Optional.empty();
  }

  /** The message of a problem with one node, naming the node first. */
  private static String nodeProblem(final WorkflowDefinition.Node node, final String message) {
    return "Node '" + node.nodeId() + "': " + message;
  }

  /** False only for a node whose plugin is known and is not a processor. */
  private static boolean isProcessor(
      final WorkflowDefinition.Node node, final PluginRegistry registry) {
    final var plugin = registry.get(node.type());
    return plugin == null || plugin.getCategory() == PluginCategory.PROCESSOR;
  }

  /** The node's idle timeout, or null if it is unknown or not positive. */
  private static Duration nodeTimeout(
      final WorkflowDefinition.Node node, final PluginRegistry registry) {
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
  private static Optional<String> findLongBackoff(
      final WorkflowDefinition.Node node, final NodePolicy policy, final PluginRegistry registry) {
    final Duration element = policy.elementTimeout();
    final Duration deadline = policy.deadline();
    if (deadline != null && element != null && element.compareTo(deadline) >= 0) {
      return Optional.of(
          nodeProblem(
              node,
              "engine.elementTimeoutMs must be below engine.deadlineMs ("
                  + deadline.toMillis()
                  + " ms), which would end the node first"));
    }
    final Duration timeout = nodeTimeout(node, registry);
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
    return nodeProblem(
        node,
        option + " must be below the node timeout (" + timeout.toSeconds() + " s), " + consequence);
  }
}
