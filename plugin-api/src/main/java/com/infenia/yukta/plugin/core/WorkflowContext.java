// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.List;

/**
 * Minimal graph context passed to plugins for workflow-level validation. Contains the edges
 * adjacent to a given node, allowing plugins to validate DAG constraints without direct access to
 * the full WorkflowDefinition.
 */
public record WorkflowContext(
    String nodeId, List<WorkflowEdge> outgoingEdges, List<WorkflowEdge> incomingEdges) {

  /**
   * Compact constructor that copies the edge lists, so later changes by the caller are not seen.
   *
   * @param nodeId the node ID
   * @param outgoingEdges the outgoing edges
   * @param incomingEdges the incoming edges
   */
  public WorkflowContext {
    outgoingEdges = List.copyOf(outgoingEdges);
    incomingEdges = List.copyOf(incomingEdges);
  }

  /**
   * Represents a single edge in the workflow graph.
   *
   * @param source source node ID
   * @param target target node ID
   * @param sourcePort source port name (optional)
   */
  public record WorkflowEdge(String source, String target, String sourcePort) {}
}
