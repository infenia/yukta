// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testrun;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.experimental.UtilityClass;

/**
 * Test runs: a draft run from the editor under a reserved workflow ID, cut down to the part the
 * editor asked about. Nothing is stored under the ID, and the execution log drops its events.
 */
@UtilityClass
public class DraftRuns {

  /** The start of every test run's workflow ID. */
  public static final String PREFIX = "__test__.";

  /** The terminal added after a node that is not one, so the cut-down graph stays valid. */
  /* default */ static final String SINK = PREFIX + "sink";

  /** The trigger that feeds the sample to a node tested on its own. */
  /* default */ static final String TRIGGER = PREFIX + "trigger";

  /** An edge from a node on every port but "error". */
  private static final String ALL_PORTS = "*";

  /**
   * A fresh test run workflow ID.
   *
   * @return {@code __test__.<uuid>}
   */
  public static String newWorkflowId() {
    return PREFIX + UUID.randomUUID();
  }

  /**
   * Whether a workflow ID is a test run's.
   *
   * @param workflowId the ID, may be null
   * @return true for a reserved ID
   */
  public static boolean isTestRun(final String workflowId) {
    return workflowId != null && workflowId.startsWith(PREFIX);
  }

  /**
   * The draft to run: the whole draft, a node with its ancestors, or a node alone.
   *
   * @param draft the draft from the editor
   * @param workflowId the ID the run gets
   * @param untilNodeId the node to run up to, or null for the whole draft
   * @param stepOnly run only {@code untilNodeId}, fed by a trigger that emits the start payload
   * @param isTerminal whether a plugin type is a terminal
   * @return the definition to prepare and run, without supervisors
   * @throws IllegalArgumentException if the node is not in the draft, or {@code stepOnly} has none
   */
  public static WorkflowDefinition cut(
      final WorkflowDefinition draft,
      final String workflowId,
      final String untilNodeId,
      final boolean stepOnly,
      final Predicate<String> isTerminal) {
    if (untilNodeId == null) {
      if (stepOnly) {
        throw new IllegalArgumentException("stepOnly needs untilNodeId");
      }
      return build(draft, workflowId, draft.nodes(), draft.edges());
    }
    final WorkflowDefinition.Node target =
        draft.nodes().stream()
            .filter(node -> node.nodeId().equals(untilNodeId))
            .findFirst()
            .orElseThrow(() ->
                new IllegalArgumentException("Node " + untilNodeId + " is not in the workflow"));
    final List<WorkflowDefinition.Node> nodes = new ArrayList<>();
    final List<WorkflowDefinition.Edge> edges = new ArrayList<>();
    if (stepOnly) {
      nodes.add(new WorkflowDefinition.Node(TRIGGER, "API_TRIGGER", Map.of(), null, null));
      nodes.add(target);
      edges.add(new WorkflowDefinition.Edge(TRIGGER, untilNodeId, ALL_PORTS));
    } else {
      final Set<String> keep = ancestorsOf(draft, untilNodeId);
      draft.nodes().stream().filter(node -> keep.contains(node.nodeId())).forEach(nodes::add);
      draft.edges().stream()
          .filter(edge -> keep.contains(edge.source()) && keep.contains(edge.target()))
          .forEach(edges::add);
    }
    if (!isTerminal.test(target.type())) {
      nodes.add(new WorkflowDefinition.Node(SINK, "CONSOLE_TERMINAL", Map.of(), null, null));
      edges.add(new WorkflowDefinition.Edge(untilNodeId, SINK, ALL_PORTS));
    }
    return build(draft, workflowId, nodes, edges);
  }

  private static WorkflowDefinition build(
      final WorkflowDefinition draft,
      final String workflowId,
      final List<WorkflowDefinition.Node> nodes,
      final List<WorkflowDefinition.Edge> edges) {
    return new WorkflowDefinition(
        workflowId,
        draft.description(),
        nodes,
        edges,
        List.of(),
        draft.groupColors(),
        draft.admission());
  }

  /** The node and every node with a path to it. */
  private static Set<String> ancestorsOf(final WorkflowDefinition draft, final String nodeId) {
    final Set<String> seen = new LinkedHashSet<>();
    final Deque<String> open = new ArrayDeque<>(List.of(nodeId));
    while (!open.isEmpty()) {
      final String next = open.pop();
      if (seen.add(next)) {
        draft.edges().stream()
            .filter(edge -> edge.target().equals(next))
            .map(WorkflowDefinition.Edge::source)
            .forEach(open::push);
      }
    }
    return seen;
  }
}
