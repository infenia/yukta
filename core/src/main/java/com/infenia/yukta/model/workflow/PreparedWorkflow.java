// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import java.util.Map;

/**
 * An optimized execution state for a workflow.
 *
 * @param edges the internal edge list for this workflow's DAG
 * @param adjacencyList map of nodeId to its child nodes
 * @param parentsList map of nodeId to its parent nodes
 * @param pluginCache map of nodeId to its initialized plugin instance
 * @param topologicalOrder list of nodes in topological order
 * @param template the pre-compiled workflow template
 * @param durable whether an interrupted run may be resumed (the definition's {@code durable})
 */
public record PreparedWorkflow(
    List<WorkflowEdge> edges,
    Map<String, List<WorkflowNode>> adjacencyList,
    Map<String, List<WorkflowNode>> parentsList,
    Map<String, Plugin> pluginCache,
    List<WorkflowNode> topologicalOrder,
    WorkflowTemplate template,
    boolean durable) {

  /** Compact constructor to ensure immutability. */
  public PreparedWorkflow {
    edges = List.copyOf(edges);
    adjacencyList = Map.copyOf(adjacencyList);
    parentsList = Map.copyOf(parentsList);
    pluginCache = Map.copyOf(pluginCache);
    topologicalOrder = List.copyOf(topologicalOrder);
  }

  /**
   * A prepared workflow whose runs may be resumed.
   *
   * @param edges the internal edge list for this workflow's DAG
   * @param adjacencyList map of nodeId to its child nodes
   * @param parentsList map of nodeId to its parent nodes
   * @param pluginCache map of nodeId to its initialized plugin instance
   * @param topologicalOrder list of nodes in topological order
   * @param template the pre-compiled workflow template
   */
  public PreparedWorkflow(
      final List<WorkflowEdge> edges,
      final Map<String, List<WorkflowNode>> adjacencyList,
      final Map<String, List<WorkflowNode>> parentsList,
      final Map<String, Plugin> pluginCache,
      final List<WorkflowNode> topologicalOrder,
      final WorkflowTemplate template) {
    this(edges, adjacencyList, parentsList, pluginCache, topologicalOrder, template, true);
  }
}
