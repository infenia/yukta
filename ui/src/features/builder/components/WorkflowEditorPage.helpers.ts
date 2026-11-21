// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowGraph } from '../../workflow/types/workflow';
import type { DraftWorkflow } from '../types';

/**
 * Why `source -> target` cannot be an edge, or null when it can: the rules the server enforces
 * on Validate, so a drawn edge is refused at once instead of at the end. A node whose category is
 * unknown (its plugin did not load) is not judged.
 */
export function connectionProblem(
  workflow: DraftWorkflow,
  categories: Record<string, string>,
  source: string,
  target: string
): string | null {
  const typeOf = (id: string) =>
    workflow.nodes.find((n) => n.nodeId === id)?.type;
  const categoryOf = (id: string) => categories[typeOf(id) ?? ''];
  if (source === target) return 'An edge cannot connect a node to itself.';
  if (categoryOf(source) === 'TERMINAL') {
    return 'A terminal ends a branch, so nothing can follow it.';
  }
  if (categoryOf(target) === 'TRIGGER') {
    return 'A trigger starts a workflow, so nothing can feed into it.';
  }
  // a loop: the target already leads back to the source
  const seen = new Set<string>();
  const stack = [target];
  while (stack.length > 0) {
    const id = stack.pop() as string;
    if (id === source) return 'That edge would make a loop.';
    if (!seen.has(id)) {
      seen.add(id);
      workflow.edges
        .filter((e) => e.source === id)
        .forEach((e) => stack.push(e.target));
    }
  }
  return null;
}

/** Draft nodes and edges shaped like the read-only graph so the existing canvas can preview them. */
export function toPreviewGraph(
  workflow: DraftWorkflow,
  categories: Record<string, string>,
  ports: Record<string, string[]>,
  issues: Record<string, { errors: number; warnings: number }> = {}
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
      color: node.color,
      issues: issues[node.nodeId],
    })),
    edges: workflow.edges,
    topologicalOrder: [],
    groupColors: workflow.groupColors,
  };
}
