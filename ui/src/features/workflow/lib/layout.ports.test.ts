// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { layoutGraph, type WorkflowFlowNode } from './layout';
import type { WorkflowGraph, WorkflowGraphNode } from '../types/workflow';

const n = (nodeId: string, outputPorts: string[] = []) =>
  ({ nodeId, outputPorts, inputPorts: [] }) as unknown as WorkflowGraphNode;

describe('layoutGraph output port order', () => {
  it('lists ports top to bottom by where their targets are placed', () => {
    const graph = {
      nodes: [n('a', ['x', 'y']), n('t1'), n('t2')],
      edges: [
        { source: 'a', target: 't1', sourcePort: 'x' },
        { source: 'a', target: 't2', sourcePort: 'y' },
      ],
    } as unknown as WorkflowGraph;
    const { nodes } = layoutGraph(graph, {});
    const byId = (id: string) =>
      nodes.find((c) => c.id === id) as WorkflowFlowNode;
    const [first, second] = byId('a').data.node.outputPorts;
    const portTarget = { x: 't1', y: 't2' } as Record<string, string>;
    expect(byId(portTarget[first]).position.y).toBeLessThan(
      byId(portTarget[second]).position.y
    );
  });
});
