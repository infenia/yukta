// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import java.time.Duration;

/** Strategy for assembling workflow nodes with appropriate operators. */
public interface NodeAssemblerStrategy {

  /**
   * Checks if this strategy supports the given plugin.
   *
   * @param plugin the workflow plugin
   * @param hasParents whether the node has parent nodes
   * @return true if this strategy can handle the plugin, false otherwise
   */
  boolean supports(Plugin plugin, boolean hasParents);

  /**
   * Creates a node assembler for the given workflow node.
   *
   * @param node the workflow node to assemble
   * @param plugin the workflow plugin
   * @param timeout the processing timeout
   * @param index the node index in the workflow
   * @param bufferSize the buffer size for streams
   * @param parentEdges the parent edges connecting to this node
   * @return a configured node assembler
   */
  NodeAssembler createAssembler(
      WorkflowNode node,
      Plugin plugin,
      Duration timeout,
      int index,
      int bufferSize,
      ParentEdgeInfo... parentEdges);
}
