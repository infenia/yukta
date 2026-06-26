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
 */
public record PreparedWorkflow(
    List<WorkflowEdge> edges,
    Map<String, List<WorkflowNode>> adjacencyList,
    Map<String, List<WorkflowNode>> parentsList,
    Map<String, Plugin> pluginCache,
    List<WorkflowNode> topologicalOrder,
    WorkflowTemplate template) {

  /** Compact constructor to ensure immutability. */
  public PreparedWorkflow {
    edges = List.copyOf(edges);
    adjacencyList = Map.copyOf(adjacencyList);
    parentsList = Map.copyOf(parentsList);
    pluginCache = Map.copyOf(pluginCache);
    topologicalOrder = List.copyOf(topologicalOrder);
  }
}
