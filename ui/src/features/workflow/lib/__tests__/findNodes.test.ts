// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { findNodes } from '../findNodes';
import type { WorkflowGraph } from '../../types/workflow';

const node = (nodeId: string, extra = {}) => ({
  nodeId,
  type: 'PROCESSOR',
  category: null,
  description: null,
  uiDesign: null,
  outputPorts: [],
  ...extra,
});
const graph = {
  workflowId: 'w',
  description: '',
  nodes: [
    node('fetch', { description: 'Download the archive' }),
    node('parse', { group: 'etl' }),
    node('store'),
  ],
  edges: [{ source: 'fetch', target: 'parse', sourcePort: null }],
  topologicalOrder: [],
} as WorkflowGraph;

describe('findNodes', () => {
  it('matches several nodes by id, description, stage and edge', () => {
    expect(findNodes(graph, 'st')).toEqual([{ nodeId: 'store', via: 'id' }]);
    expect(findNodes(graph, 'archive')).toEqual([
      { nodeId: 'fetch', via: 'description' },
    ]);
    expect(findNodes(graph, 'etl')).toEqual([
      { nodeId: 'parse', via: 'stage' },
    ]);
    expect(findNodes(graph, 'fetch->parse').map((m) => m.nodeId)).toEqual([
      'fetch',
      'parse',
    ]);
    expect(findNodes(graph, 'processor')).toHaveLength(3);
    expect(findNodes(graph, '  ')).toEqual([]);
  });
});
