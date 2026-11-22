// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { flowLayout } from '../flowLayout';

describe('flowLayout', () => {
  it('is empty without nodes', () => {
    expect(flowLayout([], [])).toEqual({ nodes: [], edges: [], waves: 0 });
  });

  it('keeps the color a node declares', () => {
    const flow = flowLayout(
      [
        { nodeId: 'a', type: 'MAPPER', color: 'teal' },
        { nodeId: 'b', type: 'MAPPER' },
      ],
      [{ source: 'a', target: 'b' }]
    );
    expect(flow.nodes.map((n) => n.color)).toEqual(['teal', undefined]);
  });

  it('puts a chain in consecutive waves, left to right', () => {
    const flow = flowLayout(
      ['a', 'b', 'c'].map((nodeId) => ({ nodeId, type: 'MAPPER' })),
      [
        { source: 'a', target: 'b' },
        { source: 'b', target: 'c' },
      ]
    );
    expect(flow.waves).toBe(3);
    expect(flow.nodes.map((n) => n.wave)).toEqual([0, 1, 2]);
    expect(flow.nodes[0].x).toBeLessThan(flow.nodes[2].x);
  });

  it('runs a branch in one wave, drops dangling edges and survives a loop', () => {
    const flow = flowLayout(
      ['a', 'b', 'c'].map((nodeId) => ({ nodeId, type: 'MAPPER' })),
      [
        { source: 'a', target: 'b' },
        { source: 'a', target: 'c' },
        { source: 'b', target: 'a' },
        { source: 'a', target: 'gone' },
      ]
    );
    expect(flow.edges).toHaveLength(3);
    expect(flow.nodes.find((n) => n.id === 'b')?.wave).toBe(
      flow.nodes.find((n) => n.id === 'c')?.wave
    );
  });
});
