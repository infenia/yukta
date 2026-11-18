// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowGraph } from '../types/workflow';

export interface NodeMatch {
  nodeId: string;
  /** What matched: the ID, the plugin type, the stage, the description, or an edge. */
  via: 'id' | 'type' | 'stage' | 'description' | 'edge';
}

/**
 * The nodes a search matches: by ID, plugin type, stage or description, plus both ends of an edge
 * when the query is an arrow ("source -> target"). Every term must match (case-insensitive), so
 * "http fetch" narrows rather than widens.
 */
export function findNodes(graph: WorkflowGraph, query: string): NodeMatch[] {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return [];
  const all = (text: string) => terms.every((term) => text.includes(term));
  const found = new Map<string, NodeMatch['via']>();
  for (const node of graph.nodes) {
    const via =
      (all(node.nodeId.toLowerCase()) && 'id') ||
      (all(node.type.toLowerCase()) && 'type') ||
      (all((node.group ?? '').toLowerCase()) && node.group && 'stage') ||
      (all((node.description ?? '').toLowerCase()) &&
        node.description &&
        'description');
    if (via) found.set(node.nodeId, via);
  }
  // "a -> b": both ends of an edge, only when the query has an arrow
  const arrow = query
    .toLowerCase()
    .replace(/\s*->\s*/g, ' -> ')
    .trim();
  if (arrow.includes('->')) {
    for (const { source, target } of graph.edges) {
      if (`${source} -> ${target}`.toLowerCase().includes(arrow)) {
        for (const id of [source, target]) {
          if (!found.has(id)) found.set(id, 'edge');
        }
      }
    }
  }
  return [...found].map(([nodeId, via]) => ({ nodeId, via }));
}
