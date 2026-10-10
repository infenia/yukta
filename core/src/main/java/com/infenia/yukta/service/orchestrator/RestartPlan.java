// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which nodes a restart from a given node re-runs, and whose recorded output it replays.
 *
 * <p>The restart node and all of its descendants run again. Nodes that are not descendants do not
 * run, except the ones that feed the re-run set: their recorded output is replayed into it. This
 * leaves independent branches untouched, so their side effects are not repeated.
 *
 * @param rerun the restart node and its descendants
 * @param boundary nodes outside {@code rerun} that feed it, whose recorded output is replayed
 */
public record RestartPlan(Set<String> rerun, Set<String> boundary) {

  /**
   * Keeps immutable copies of the node sets.
   *
   * @param rerun the restart node and its descendants
   * @param boundary nodes outside {@code rerun} that feed it
   */
  public RestartPlan {
    rerun = Set.copyOf(rerun);
    boundary = Set.copyOf(boundary);
  }

  /**
   * Computes the plan.
   *
   * @param prepared the workflow graph
   * @param fromNodeId the node to restart from
   * @return the plan
   * @throws IllegalArgumentException if the node is not part of the workflow
   */
  public static RestartPlan compute(final PreparedWorkflow prepared, final String fromNodeId) {
    final boolean known =
        prepared.topologicalOrder().stream().anyMatch(n -> n.nodeId().equals(fromNodeId));
    if (!known) {
      throw new IllegalArgumentException("Unknown restart node: " + fromNodeId);
    }
    final Set<String> rerun = new LinkedHashSet<>();
    final Deque<String> pending = new ArrayDeque<>(List.of(fromNodeId));
    while (!pending.isEmpty()) {
      final String node = pending.removeFirst();
      if (rerun.add(node)) {
        prepared.adjacencyList().getOrDefault(node, List.of()).stream()
            .map(WorkflowNode::nodeId)
            .forEach(pending::addLast);
      }
    }
    final Set<String> boundary = new LinkedHashSet<>();
    for (final String node : rerun) {
      prepared.parentsList().getOrDefault(node, List.of()).stream()
          .map(WorkflowNode::nodeId)
          .filter(parent -> !rerun.contains(parent))
          .forEach(boundary::add);
    }
    return new RestartPlan(Set.copyOf(rerun), Set.copyOf(boundary));
  }
}
