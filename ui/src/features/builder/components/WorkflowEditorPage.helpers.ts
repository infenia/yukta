// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowGraph } from '../../workflow/types/workflow';
import type { DraftWorkflow } from '../types';

/** Draft nodes and edges shaped like the read-only graph so the existing canvas can preview them. */
export function toPreviewGraph(
  workflow: DraftWorkflow,
  categories: Record<string, string>,
  ports: Record<string, string[]>
): WorkflowGraph {
  return {
    workflowId: workflow.workflowId,
    description: workflow.description,
    nodes: workflow.nodes.map((node) => ({
      nodeId: node.nodeId,
      type: node.type,
      category:
        (categories[node.type] as WorkflowGraph['nodes'][number]['category']) ??
        null,
      description: null,
      uiDesign: null,
      outputPorts: ports[node.nodeId] ?? ['default'],
      group: node.group,
    })),
    edges: workflow.edges,
    topologicalOrder: [],
  };
}
