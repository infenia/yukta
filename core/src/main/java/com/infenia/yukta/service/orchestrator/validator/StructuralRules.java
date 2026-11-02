// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The eager structural check of a workflow definition: size, ids, edges and their output ports. It
 * runs before the graph checks, which assume that the structure is sound.
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
}
