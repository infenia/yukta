// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.preparator;

import com.infenia.yukta.model.workflow.WorkflowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Service for computing topological sort of workflow DAG nodes. */
@Slf4j
@Service
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "checkstyle:AvoidStarImport",
  "PMD.UseConcurrentHashMap"
})
public class TopologicalSortService {

  /**
   * Compute the topological order of nodes using Kahn's algorithm.
   *
   * @param nodes the list of nodes in the workflow
   * @param adj the adjacency list map of nodeId to child nodes
   * @param parents the parents list map of nodeId to parent nodes
   * @return the list of nodes in topological order
   * @throws IllegalArgumentException if the workflow DAG contains cycles or is invalid
   */
  public List<WorkflowNode> computeTopologicalOrder(
      final List<WorkflowNode> nodes,
      final Map<String, List<WorkflowNode>> adj,
      final Map<String, List<WorkflowNode>> parents) {
    log.atDebug()
        .addKeyValue("nodeCount", nodes.size())
        .addKeyValue("edgeCount", adj.size())
        .log("Starting topological sort of workflow DAG");

    final Map<String, Integer> inDegree = new HashMap<>();
    final Map<String, WorkflowNode> nodeMap = new HashMap<>();

    for (final WorkflowNode node : nodes) {
      nodeMap.put(node.nodeId(), node);
      inDegree.put(node.nodeId(), parents.getOrDefault(node.nodeId(), List.of()).size());
    }

    final Queue<String> queue = new ArrayDeque<>();
    inDegree.forEach(
        (id, degree) -> {
          if (degree == 0) {
            queue.add(id);
            log.atDebug().addKeyValue("nodeId", id).log("Added root node to processing queue");
          }
        });

    final List<WorkflowNode> result = new ArrayList<>();
    while (!queue.isEmpty()) {
      final String sourceNodeId = queue.poll();
      result.add(nodeMap.get(sourceNodeId));
      log.atDebug()
          .addKeyValue("nodeId", sourceNodeId)
          .addKeyValue("processedCount", result.size())
          .log("Processing node in topological order");

      processChildren(adj.get(sourceNodeId), inDegree, queue);
    }

    if (result.size() != nodes.size()) {
      final List<String> unprocessedNodes = new ArrayList<>();
      for (final String id : inDegree.keySet()) {
        if (!result.contains(nodeMap.get(id))) {
          unprocessedNodes.add(id);
        }
      }
      log.atError()
          .addKeyValue("expectedCount", nodes.size())
          .addKeyValue("actualCount", result.size())
          .addKeyValue("unprocessedCount", unprocessedNodes.size())
          .log("Topological sort failed: DAG contains cycles or is invalid");
      throw new IllegalArgumentException("Workflow DAG contains cycles or is invalid");
    }

    log.atInfo()
        .addKeyValue("sortedNodeCount", result.size())
        .log("Completed topological sort of workflow DAG");
    return result;
  }

  private void processChildren(
      final List<WorkflowNode> children,
      final Map<String, Integer> inDegree,
      final Queue<String> queue) {
    if (children != null) {
      for (final WorkflowNode v : children) {
        final int currentDegree = inDegree.get(v.nodeId());
        final int newDegree = currentDegree - 1;
        inDegree.put(v.nodeId(), newDegree);
        log.atDebug()
            .addKeyValue("nodeId", v.nodeId())
            .addKeyValue("previousInDegree", currentDegree)
            .addKeyValue("newInDegree", newDegree)
            .log("Updated in-degree for child node");
        if (newDegree == 0) {
          queue.add(v.nodeId());
          log.atDebug()
              .addKeyValue("nodeId", v.nodeId())
              .log("Child node is now ready for processing (in-degree = 0)");
        }
      }
    }
  }
}
