// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What changed between two versions of a workflow definition, by node and edge.
 *
 * @param descriptionChanged whether the description differs
 * @param addedNodes ids of nodes only the newer version has
 * @param removedNodes ids of nodes only the older version has
 * @param changedNodes ids of nodes whose type or configuration differ
 * @param addedEdges edges only the newer version has, written {@code source -> target[:port]}
 * @param removedEdges edges only the older version has
 */
public record WorkflowDiff(
    boolean descriptionChanged,
    List<String> addedNodes,
    List<String> removedNodes,
    List<String> changedNodes,
    List<String> addedEdges,
    List<String> removedEdges) {

  /** Compact constructor to ensure immutability. */
  public WorkflowDiff {
    addedNodes = List.copyOf(addedNodes);
    removedNodes = List.copyOf(removedNodes);
    changedNodes = List.copyOf(changedNodes);
    addedEdges = List.copyOf(addedEdges);
    removedEdges = List.copyOf(removedEdges);
  }

  /**
   * Compares two definitions.
   *
   * @param older the earlier version
   * @param newer the later version
   * @return the differences, each list sorted
   */
  public static WorkflowDiff between(
      final WorkflowDefinition older, final WorkflowDefinition newer) {
    final Map<String, Node> before = byId(older);
    final Map<String, Node> after = byId(newer);
    final List<String> changed = new ArrayList<>();
    before.forEach(
        (id, node) -> {
          if (after.containsKey(id) && !node.equals(after.get(id))) {
            changed.add(id);
          }
        });
    final Set<String> oldEdges =
        older.edges().stream().map(WorkflowDiff::edge).collect(Collectors.toSet());
    final Set<String> newEdges =
        newer.edges().stream().map(WorkflowDiff::edge).collect(Collectors.toSet());
    return new WorkflowDiff(
        !Objects.equals(older.description(), newer.description()),
        sorted(after.keySet().stream().filter(id -> !before.containsKey(id))),
        sorted(before.keySet().stream().filter(id -> !after.containsKey(id))),
        sorted(changed.stream()),
        sorted(newEdges.stream().filter(e -> !oldEdges.contains(e))),
        sorted(oldEdges.stream().filter(e -> !newEdges.contains(e))));
  }

  private static Map<String, Node> byId(final WorkflowDefinition definition) {
    return definition.nodes().stream()
        .collect(Collectors.toMap(Node::nodeId, Function.identity(), (a, b) -> b));
  }

  private static String edge(final Edge edge) {
    return edge.source()
        + " -> "
        + edge.target()
        + (edge.sourcePort() == null ? "" : ":" + edge.sourcePort());
  }

  private static List<String> sorted(final java.util.stream.Stream<String> values) {
    return values.sorted().toList();
  }
}
