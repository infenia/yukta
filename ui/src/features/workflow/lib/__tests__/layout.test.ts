// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { DEFAULT_NODE_HEIGHT, DEFAULT_NODE_WIDTH, layoutGraph } from '../layout';
import { createMockGraph, createMockProgress, createMockTask } from '@/test/factories/workflowFactory';
import { must } from '@/test/utils/testUtils';
import type { TaskProgress } from '../../types/workflow';

function tasksByNode(tasks: TaskProgress[]): Record<string, TaskProgress> {
  return Object.fromEntries(tasks.map((task) => [task.nodeId, task]));
}

describe('layoutGraph', () => {
  it('lays out nodes left-to-right by rank', () => {
    const { nodes } = layoutGraph(createMockGraph(), {});
    const byId = new Map(nodes.map((node) => [node.id, node]));

    const trigger = must(byId.get('data-ingress'));
    const processor = must(byId.get('vectorize-batch'));
    const terminal = must(byId.get('sink-storage'));

    expect(trigger.position.x).toBeLessThan(processor.position.x);
    expect(processor.position.x).toBeLessThan(terminal.position.x);
  });

  it('produces one canvas node per graph node with placeholder type and data', () => {
    const graph = createMockGraph();
    const progress = createMockProgress();
    const { nodes } = layoutGraph(graph, tasksByNode(progress.tasks));

    expect(nodes).toHaveLength(4);
    nodes.forEach((node) => {
      expect(node.type).toBe('workflowNode');
      expect(node.draggable).toBe(false);
    });

    const running = must(nodes.find((node) => node.id === 'vectorize-batch'));
    expect(running.data.status).toBe('running');
    expect(running.data.task?.metadata).toEqual({ progress: 42 });

    const pending = must(nodes.find((node) => node.id === 'sink-storage'));
    expect(pending.data.status).toBe('pending');
  });

  it('sizes nodes from uiDesign with chrome, and defaults otherwise', () => {
    const { nodes } = layoutGraph(createMockGraph(), {});
    const withDesign = must(nodes.find((node) => node.id === 'vectorize-batch'));
    const withoutDesign = must(nodes.find((node) => node.id === 'data-ingress'));

    expect(withDesign.width).toBe(DEFAULT_NODE_WIDTH);
    expect(withDesign.height).toBe(96);
    expect(withoutDesign.width).toBe(DEFAULT_NODE_WIDTH);
    expect(withoutDesign.height).toBe(DEFAULT_NODE_HEIGHT);
  });

  it('widens nodes whose uiDesign is wider than the default', () => {
    const graph = createMockGraph();
    graph.nodes[1] = {
      ...graph.nodes[1],
      uiDesign: { html: '<div/>', width: 400, height: 100 },
    };
    const { nodes } = layoutGraph(graph, {});
    expect(must(nodes.find((node) => node.id === 'vectorize-batch')).width).toBe(400);
  });

  it('wires sourceHandle from the edge sourcePort when the port exists', () => {
    const { edges } = layoutGraph(createMockGraph(), {});
    const portEdge = must(edges.find((edge) => edge.source === 'vectorize-batch'));
    expect(portEdge.sourceHandle).toBe('default');
  });

  it('falls back to the first output port when the edge has no port', () => {
    const { edges } = layoutGraph(createMockGraph(), {});
    const fallbackEdge = must(edges.find(
      (edge) => edge.source === 'data-ingress' && edge.target === 'vectorize-batch'
    ));
    expect(fallbackEdge.sourceHandle).toBe('default');
  });

  it('falls back to the first port when the edge names an unknown port', () => {
    const graph = createMockGraph();
    graph.edges[2] = { ...graph.edges[2], sourcePort: 'ghost-port' };
    const { edges } = layoutGraph(graph, {});
    const edge = must(edges.find((e) => e.source === 'vectorize-batch'));
    expect(edge.sourceHandle).toBe('default');
  });

  it('leaves sourceHandle undefined for nodes without output ports', () => {
    const graph = createMockGraph();
    graph.nodes[0] = { ...graph.nodes[0], outputPorts: [] };
    const { edges } = layoutGraph(graph, {});
    const edge = must(edges.find((e) => e.source === 'data-ingress'));
    expect(edge.sourceHandle).toBeUndefined();
  });

  it('marks edges from completed sources as traversed, others as inactive', () => {
    const progress = createMockProgress();
    const { edges } = layoutGraph(createMockGraph(), tasksByNode(progress.tasks));

    const traversed = must(edges.find(
      (edge) => edge.source === 'data-ingress' && edge.target === 'vectorize-batch'
    ));
    expect(traversed.style?.stroke).toBe('var(--color-primary)');
    expect(traversed.animated).toBe(true);

    const traversedToPending = must(edges.find(
      (edge) => edge.source === 'data-ingress' && edge.target === 'anomaly-detection'
    ));
    expect(traversedToPending.style?.stroke).toBe('var(--color-primary)');
    expect(traversedToPending.animated).toBe(false);

    const inactive = must(edges.find((edge) => edge.source === 'vectorize-batch'));
    expect(inactive.style?.stroke).toBe('var(--color-outline)');
    expect(inactive.animated).toBe(false);
  });

  it('drops edges referencing unknown nodes', () => {
    const graph = createMockGraph();
    graph.edges = [...graph.edges, { source: 'ghost', target: 'sink-storage', sourcePort: null }];
    const { edges } = layoutGraph(graph, {});
    expect(edges).toHaveLength(4);
  });

  it('handles a never-run workflow with every node pending', () => {
    const { nodes, edges } = layoutGraph(createMockGraph(), {});
    expect(nodes.every((node) => node.data.status === 'pending')).toBe(true);
    expect(edges.every((edge) => edge.animated === false)).toBe(true);
  });

  it('treats a failed source edge as inactive', () => {
    const tasks = tasksByNode([
      createMockTask({ nodeId: 'data-ingress', status: 'FAILURE', endTime: null }),
    ]);
    const { edges } = layoutGraph(createMockGraph(), tasks);
    const edge = must(edges.find((e) => e.source === 'data-ingress'));
    expect(edge.style?.stroke).toBe('var(--color-outline)');
  });
});
