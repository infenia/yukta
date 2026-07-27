// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.orchestrator.preparator.TopologicalSortService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Core domain service for workflow graph enrichment and ordering. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowGraphService {

  /** The registry of available plugins. */
  private final PluginRegistry pluginRegistry;

  /** The service computing topological node order. */
  private final TopologicalSortService topologicalSortService;

  /** Plugin metadata for a node. */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public record EnrichedNodeMetadata(
      String nodeId,
      String type,
      PluginCategory category,
      String description,
      UiDesign uiDesign,
      List<String> outputPorts) {
    /** Compact constructor ensuring outputPorts is immutable. */
    public EnrichedNodeMetadata {
      if (outputPorts != null) {
        outputPorts = List.copyOf(outputPorts);
      }
    }
  }

  /**
   * Enriches a workflow node with plugin metadata.
   *
   * @param node the workflow node to enrich
   * @return the enriched node metadata
   */
  public EnrichedNodeMetadata enrichNode(final WorkflowDefinition.Node node) {
    final Plugin plugin = pluginRegistry.get(node.type());
    final EnrichedNodeMetadata enriched;
    if (plugin == null) {
      log.atWarn()
          .addKeyValue("nodeId", node.nodeId())
          .addKeyValue("pluginType", node.type())
          .log("Unknown plugin type for workflow node, returning bare node");
      enriched = new EnrichedNodeMetadata(node.nodeId(), node.type(), null, null, null, List.of());
    } else {
      enriched =
          new EnrichedNodeMetadata(
              node.nodeId(),
              node.type(),
              plugin.getCategory(),
              plugin.getDescription(),
              plugin.getUiDesign().orElse(null),
              plugin.getOutputPorts(node.config()));
    }
    return enriched;
  }

  /**
   * Computes the topological order of workflow nodes, with declaration order fallback on failure.
   *
   * @param definition the workflow definition
   * @return list of node IDs in topological order
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  public List<String> computeTopologicalOrder(final WorkflowDefinition definition) {
    final Map<String, WorkflowDefinition.Node> nodeMap = new HashMap<>();
    final Map<String, List<WorkflowNode>> adjacency = new HashMap<>();
    final Map<String, List<WorkflowNode>> parents = new HashMap<>();
    definition
        .nodes()
        .forEach(
            node -> {
              nodeMap.put(node.nodeId(), node);
              adjacency.put(node.nodeId(), new ArrayList<>());
              parents.put(node.nodeId(), new ArrayList<>());
            });
    definition
        .edges()
        .forEach(
            edge -> {
              final WorkflowDefinition.Node source = nodeMap.get(edge.source());
              final WorkflowDefinition.Node target = nodeMap.get(edge.target());
              if (source == null || target == null) {
                log.atWarn()
                    .addKeyValue("source", edge.source())
                    .addKeyValue("target", edge.target())
                    .log("Edge references unknown node, skipping for topological order");
                return;
              }
              adjacency
                  .get(edge.source())
                  .add(new WorkflowNode(target.nodeId(), target.type(), target.config()));
              parents
                  .get(edge.target())
                  .add(new WorkflowNode(source.nodeId(), source.type(), source.config()));
            });
    final List<WorkflowNode> workflowNodes =
        definition.nodes().stream()
            .map(node -> new WorkflowNode(node.nodeId(), node.type(), node.config()))
            .toList();
    List<String> order;
    try {
      order =
          topologicalSortService.computeTopologicalOrder(workflowNodes, adjacency, parents).stream()
              .map(WorkflowNode::nodeId)
              .toList();
    } catch (final IllegalArgumentException e) {
      log.atWarn()
          .setCause(e)
          .addKeyValue("workflowId", definition.workflowId())
          .log("Topological sort failed, falling back to declaration order");
      order = definition.nodes().stream().map(WorkflowDefinition.Node::nodeId).toList();
    }
    return order;
  }
}
