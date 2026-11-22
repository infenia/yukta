// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import dagre from '@dagrejs/dagre';

export interface FlowNode {
  id: string;
  type: string;
  /** The palette color the node declares, if any. */
  color?: string | null;
  /** 0-based execution wave: nodes of one wave run together. */
  wave: number;
  /** Centre of the node, 0-100 (percent of the flow box). */
  x: number;
  y: number;
}

export interface FlowEdge {
  source: string;
  target: string;
}

export interface Flow {
  nodes: FlowNode[];
  edges: FlowEdge[];
  waves: number;
}

const SIZE = 10;

/**
 * Lays a workflow out left to right and groups its nodes into execution waves (one per dagre rank),
 * so a card can replay the run for any template. Pure; positions are percentages so the box scales.
 * Cycles (loops) are fine: dagre breaks them and a wave is just a column.
 */
export function flowLayout(
  nodes: { nodeId: string; type: string; color?: string | null }[],
  edges: { source: string; target: string }[]
): Flow {
  if (nodes.length === 0) return { nodes: [], edges: [], waves: 0 };
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: 'LR',
    nodesep: 6,
    ranksep: 14,
    marginx: 0,
    marginy: 0,
  });
  g.setDefaultEdgeLabel(() => ({}));
  nodes.forEach((n) => g.setNode(n.nodeId, { width: SIZE, height: SIZE }));
  const known = new Set(nodes.map((n) => n.nodeId));
  const valid = edges.filter((e) => known.has(e.source) && known.has(e.target));
  valid.forEach((e) => g.setEdge(e.source, e.target));
  dagre.layout(g);

  const { width = 1, height = 1 } = g.graph();
  const columns = [...new Set(nodes.map((n) => g.node(n.nodeId).x))].sort(
    (a, b) => a - b
  );
  // a single row or column sits in the middle instead of on the edge
  const pct = (v: number, total: number) =>
    total <= SIZE ? 50 : 4 + ((v - SIZE / 2) / (total - SIZE)) * 92;
  return {
    nodes: nodes.map((n) => {
      const { x, y } = g.node(n.nodeId);
      return {
        id: n.nodeId,
        type: n.type,
        color: n.color,
        wave: columns.indexOf(x),
        x: pct(x, width),
        y: pct(y, height),
      };
    }),
    edges: valid.map(({ source, target }) => ({ source, target })),
    waves: columns.length,
  };
}
