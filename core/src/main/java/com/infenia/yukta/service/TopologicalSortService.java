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
package com.infenia.yukta.service;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Service for computing topological sort of workflow DAG nodes. */
@Slf4j
@Service
@NoArgsConstructor
public class TopologicalSortService {

  /**
   * Compute the topological order of nodes using Kahn's algorithm.
   *
   * @param def the workflow definition
   * @param adj the adjacency list map of nodeId to child nodes
   * @param parents the parents list map of nodeId to parent nodes
   * @return the list of nodes in topological order
   * @throws IllegalArgumentException if the workflow DAG contains cycles or is invalid
   */
  public List<Node> computeTopologicalOrder(
      final WorkflowDefinition def,
      final Map<String, List<Node>> adj,
      final Map<String, List<Node>> parents) {
    final Map<String, Integer> inDegree = new ConcurrentHashMap<>();
    final Map<String, Node> nodeMap = new ConcurrentHashMap<>();

    for (final Node node : def.nodes()) {
      nodeMap.put(node.nodeId(), node);
      inDegree.put(node.nodeId(), parents.getOrDefault(node.nodeId(), List.of()).size());
    }

    final Queue<String> queue = new ArrayDeque<>();
    inDegree.forEach(
        (id, degree) -> {
          if (degree == 0) {
            queue.add(id);
          }
        });

    final List<Node> result = new ArrayList<>();
    while (!queue.isEmpty()) {
      final String sourceNodeId = queue.poll();
      result.add(nodeMap.get(sourceNodeId));

      processChildren(adj.get(sourceNodeId), inDegree, queue);
    }

    if (result.size() != def.nodes().size()) {
      throw new IllegalArgumentException("Workflow DAG contains cycles or is invalid");
    }

    return result;
  }

  private void processChildren(
      final List<Node> children, final Map<String, Integer> inDegree, final Queue<String> queue) {
    if (children != null) {
      for (final Node v : children) {
        final int degree = inDegree.get(v.nodeId()) - 1;
        inDegree.put(v.nodeId(), degree);
        if (degree == 0) {
          queue.add(v.nodeId());
        }
      }
    }
  }
}
