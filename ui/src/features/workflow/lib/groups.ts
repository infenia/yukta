// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { NodeUiStatus, WorkflowGraphNode } from '../types/workflow';

/** The stage a node belongs to: its declared group, else its node-ID prefix before the first dot. */
export function stageOf(node: WorkflowGraphNode): string {
  return node.group || node.nodeId.split('.')[0];
}

/** Stages of two or more nodes, in the order their first node appears; a lone node is no stage. */
export function groupNodes(
  nodes: WorkflowGraphNode[]
): Map<string, WorkflowGraphNode[]> {
  const stages = new Map<string, WorkflowGraphNode[]>();
  nodes.forEach((node) => {
    const stage = stageOf(node);
    stages.set(stage, [...(stages.get(stage) ?? []), node]);
  });
  return new Map([...stages].filter(([, members]) => members.length > 1));
}

/** One status for a stage: any failure wins, then anything running or paused, then all done. */
export function aggregateStatus(statuses: NodeUiStatus[]): NodeUiStatus {
  for (const status of ['failed', 'running', 'paused'] as const) {
    if (statuses.includes(status)) return status;
  }
  if (statuses.every((s) => s === 'skipped')) return 'skipped';
  return statuses.every((s) => s === 'completed' || s === 'skipped')
    ? 'completed'
    : 'pending';
}
