// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A workflow DAG with nodes enriched by plugin metadata, ready for UI canvas rendering.
 *
 * @param workflowId the workflow identifier
 * @param description the workflow description
 * @param nodes the enriched DAG nodes
 * @param edges the DAG edges
 * @param topologicalOrder node identifiers in topological execution order
 */
@Schema(description = "A workflow DAG with plugin-enriched nodes for UI canvas rendering")
public record WorkflowGraph(
    @Schema(description = "The workflow identifier") String workflowId,
    @Schema(description = "The workflow description") String description,
    @Schema(description = "The enriched DAG nodes") List<WorkflowGraphNode> nodes,
    @Schema(description = "The DAG edges") List<WorkflowGraphEdge> edges,
    @Schema(description = "Node identifiers in topological execution order")
        List<String> topologicalOrder) {

  /** Compact constructor to ensure immutability. */
  public WorkflowGraph {
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
    edges = edges != null ? List.copyOf(edges) : List.of();
    topologicalOrder = topologicalOrder != null ? List.copyOf(topologicalOrder) : List.of();
  }
}
