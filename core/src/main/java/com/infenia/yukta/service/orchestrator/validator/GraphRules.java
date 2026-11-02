// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The synchronous checks of the graph (plugin lookup, trigger and terminal nodes, entry points,
 * endpoints, cycles), each giving at most its first problem. They assume the structure is sound.
 */
final class GraphRules {

  private GraphRules() {}

  /** True if every node has a registered plugin. */
  /* default */ static boolean allRegistered(
      final WorkflowDefinition def, final PluginRegistry registry) {
    return def.nodes().stream().allMatch(node -> registry.get(node.type()) != null);
  }

  /**
   * The problems of the graph, in the order the checks run. Every check after the plugin lookup
   * assumes each node has a registered plugin, so when one has none only the lookup is reported.
   */
  /* default */ static List<String> findProblems(
      final WorkflowDefinition def, final PluginRegistry registry) {
    final List<String> problems = new ArrayList<>();
    if (!allRegistered(def, registry)) {
      def.nodes().stream()
          .filter(node -> registry.get(node.type()) == null)
          .findFirst()
          .ifPresent(node -> problems.add("Plugin not found for type: " + node.type()));
      return problems;
    }
    final Set<String> targetIds =
        def.edges().stream().map(WorkflowDefinition.Edge::target).collect(Collectors.toSet());
    final Set<String> sourceIds =
        def.edges().stream().map(WorkflowDefinition.Edge::source).collect(Collectors.toSet());
    final List<Optional<String>> checks =
        List.of(
            requireNode(def, registry, plugin -> plugin instanceof TriggerPlugin, "TRIGGER"),
            requireNode(def, registry, GraphRules::isTerminal, "TERMINAL"),
            firstProblem(def, node -> entryPoint(node, registry, targetIds)),
            firstProblem(def, node -> processor(node, registry, sourceIds)),
            firstProblem(def, node -> endpoint(node, registry, sourceIds)),
            hasCycles(def)
                ? Optional.of("Workflow DAG contains cycles")
                : Optional.<String>empty());
    checks.forEach(check -> check.ifPresent(problems::add));
    return problems;
  }

  private static Optional<String> firstProblem(
      final WorkflowDefinition def,
      final Function<WorkflowDefinition.Node, Optional<String>> check) {
    return def.nodes().stream().map(check).flatMap(Optional::stream).findFirst();
  }

  private static Optional<String> requireNode(
      final WorkflowDefinition def,
      final PluginRegistry registry,
      final Predicate<Plugin> wanted,
      final String category) {
    final boolean found = def.nodes().stream().anyMatch(n -> wanted.test(registry.get(n.type())));
    return found
        ? Optional.empty()
        : Optional.of("Workflow must contain at least one " + category + " node");
  }

  /**
   * A terminal must declare the category and be a {@link TerminalPlugin}, because the assembler
   * only builds a terminal from the type; a plugin that has only one of them is not accepted.
   */
  private static boolean isTerminal(final Plugin plugin) {
    return plugin.getCategory() == PluginCategory.TERMINAL && plugin instanceof TerminalPlugin;
  }

  private static Optional<String> entryPoint(
      final WorkflowDefinition.Node node,
      final PluginRegistry registry,
      final Set<String> targets) {
    final Plugin plugin = registry.get(node.type());
    final boolean isEntryPoint = !targets.contains(node.nodeId());
    if (isEntryPoint && !(plugin instanceof TriggerPlugin)) {
      return Optional.of("Node " + node.nodeId() + " is an entry point but not a TRIGGER");
    }
    if (!isEntryPoint && plugin.getCategory() == PluginCategory.TRIGGER) {
      return Optional.of("Trigger node " + node.nodeId() + " cannot have incoming edges");
    }
    return Optional.empty();
  }

  /** A processor without incoming edges is already rejected by {@link #entryPoint}. */
  private static Optional<String> processor(
      final WorkflowDefinition.Node node,
      final PluginRegistry registry,
      final Set<String> sources) {
    final boolean noOutgoing = !sources.contains(node.nodeId());
    return noOutgoing && registry.get(node.type()).getCategory() == PluginCategory.PROCESSOR
        ? Optional.of(
            "Processor node " + node.nodeId() + " must have both incoming and outgoing edges")
        : Optional.empty();
  }

  private static Optional<String> endpoint(
      final WorkflowDefinition.Node node,
      final PluginRegistry registry,
      final Set<String> sources) {
    final boolean isEndpoint = !sources.contains(node.nodeId());
    final boolean isTerminal = isTerminal(registry.get(node.type()));
    if (isEndpoint && !isTerminal) {
      return Optional.of("Node " + node.nodeId() + " is an endpoint but not a TERMINAL");
    }
    if (!isEndpoint && isTerminal) {
      return Optional.of("Terminal node " + node.nodeId() + " cannot have outgoing edges");
    }
    return Optional.empty();
  }

  /** Kahn's algorithm: the graph has a cycle if not every node can be taken off in order. */
  private static boolean hasCycles(final WorkflowDefinition def) {
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
