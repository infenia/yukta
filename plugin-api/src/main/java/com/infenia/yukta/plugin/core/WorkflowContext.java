// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.Collections;
import java.util.List;

/**
 * Minimal graph context passed to plugins for workflow-level validation. Contains the edges
 * adjacent to a given node, allowing plugins to validate DAG constraints without direct access to
 * the full WorkflowDefinition.
 */
public record WorkflowContext(
    String nodeId, List<WorkflowEdge> outgoingEdges, List<WorkflowEdge> incomingEdges) {

  /**
   * Compact constructor that wraps mutable lists with immutable views.
   *
   * @param nodeId the node ID
   * @param outgoingEdges the outgoing edges (will be wrapped as immutable)
   * @param incomingEdges the incoming edges (will be wrapped as immutable)
   */
  public WorkflowContext(
      final String nodeId,
      final List<WorkflowEdge> outgoingEdges,
      final List<WorkflowEdge> incomingEdges) {
    this.nodeId = nodeId;
    this.outgoingEdges = Collections.unmodifiableList(outgoingEdges);
    this.incomingEdges = Collections.unmodifiableList(incomingEdges);
  }

  /**
   * Represents a single edge in the workflow graph.
   *
   * @param source source node ID
   * @param target target node ID
   * @param sourcePort source port name (optional)
   */
  public record WorkflowEdge(String source, String target, String sourcePort) {
    /** Compact constructor for immutability. */
    public WorkflowEdge {}
  }
}
