// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import {
  DEFAULT_NODE_HEIGHT,
  DEFAULT_NODE_WIDTH,
  layoutGraph,
} from '../layout';
import {
  createStagedGraph,
  createMockGraph,
  createMockProgress,
  createMockTask,
} from '@/test/factories/workflowFactory';
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

  it('gives every node the default size', () => {
    const { nodes } = layoutGraph(createMockGraph(), {});
    for (const n of nodes) {
      expect(n.width).toBe(DEFAULT_NODE_WIDTH);
      expect(n.height).toBe(DEFAULT_NODE_HEIGHT);
    }
  });

  it('wires sourceHandle from the edge sourcePort when the port exists', () => {
    const { edges } = layoutGraph(createMockGraph(), {});
    const portEdge = must(
      edges.find((edge) => edge.source === 'vectorize-batch')
    );
    expect(portEdge.sourceHandle).toBe('default');
  });

  it('falls back to the first output port when the edge has no port', () => {
    const { edges } = layoutGraph(createMockGraph(), {});
    const fallbackEdge = must(
      edges.find(
        (edge) =>
          edge.source === 'data-ingress' && edge.target === 'vectorize-batch'
      )
    );
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
    const { edges } = layoutGraph(
      createMockGraph(),
      tasksByNode(progress.tasks)
    );

    const traversed = must(
      edges.find(
        (edge) =>
          edge.source === 'data-ingress' && edge.target === 'vectorize-batch'
      )
    );
    expect(traversed.style?.stroke).toBe('var(--color-cat-trigger)');
    expect(traversed.markerEnd).toMatchObject({
      color: 'var(--color-cat-trigger)',
    });
    expect(traversed.animated).toBe(true);

    const traversedToPending = must(
      edges.find(
        (edge) =>
          edge.source === 'data-ingress' && edge.target === 'anomaly-detection'
      )
    );
    expect(traversedToPending.style?.stroke).toBe('var(--color-cat-trigger)');
    expect(traversedToPending.animated).toBe(false);

    const inactive = must(
      edges.find((edge) => edge.source === 'vectorize-batch')
    );
    expect(inactive.style?.stroke).toBe('var(--color-outline)');
    expect(inactive.animated).toBe(false);
  });

  it('drops edges referencing unknown nodes', () => {
    const graph = createMockGraph();
    graph.edges = [
      ...graph.edges,
      { source: 'ghost', target: 'sink-storage', sourcePort: null },
    ];
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
      createMockTask({
        nodeId: 'data-ingress',
        status: 'FAILURE',
        endTime: null,
      }),
    ]);
    const { edges } = layoutGraph(createMockGraph(), tasks);
    const edge = must(edges.find((e) => e.source === 'data-ingress'));
    expect(edge.style?.stroke).toBe('var(--color-outline)');
  });

  it('colours a traversed edge with the primary colour when the source has no category', () => {
    const graph = createMockGraph();
    graph.nodes[0] = {
      ...graph.nodes[0],
      category: null,
    } as (typeof graph.nodes)[0];
    const progress = createMockProgress();
    const { edges } = layoutGraph(graph, tasksByNode(progress.tasks));

    const edge = must(edges.find((e) => e.source === 'data-ingress'));
    expect(edge.style?.stroke).toBe('var(--color-primary)');
  });
});

describe('layoutGraph with stages', () => {
  const toggle = () => undefined;
  const view = (...open: string[]) => ({
    open: new Set(open),
    onToggle: toggle,
  });

  it('draws a closed stage as one box with its count and aggregate status', () => {
    const tasks = tasksByNode([
      createMockTask({ nodeId: 'lint', status: 'SUCCESS' }),
      createMockTask({ nodeId: 'lint.fail', status: 'FAILURE' }),
    ]);
    const { nodes, edges } = layoutGraph(createStagedGraph(), tasks, view());
    expect(nodes.map((n) => n.id).sort()).toEqual([
      'end',
      'stage:lint',
      'stage:test',
      'start',
    ]);
    const lint = must(nodes.find((n) => n.id === 'stage:lint'));
    expect(lint.type).toBe('stageNode');
    expect(lint.data).toMatchObject({
      stage: 'lint',
      count: 3,
      status: 'failed',
      onToggle: toggle,
    });

    // edges inside a stage vanish, and the two lint → test edges become one without a port
    expect(edges.map((e) => `${e.source}>${e.target}`)).toEqual([
      'start>stage:lint',
      'stage:lint>stage:test',
      'stage:test>end',
    ]);
    expect(edges[1].sourceHandle).toBeUndefined();
  });

  it('frames an open stage behind its nodes, which keep their ports', () => {
    const { nodes, edges } = layoutGraph(createStagedGraph(), {}, view('lint'));
    const frame = must(nodes.find((n) => n.id === 'frame:lint'));
    expect(nodes[0]).toBe(frame);
    expect(frame).toMatchObject({
      type: 'stageFrame',
      zIndex: -1,
      selectable: false,
    });
    expect(frame.data).toMatchObject({ count: 3, status: 'pending' });
    for (const id of ['lint', 'lint.fail', 'lint.fix']) {
      const member = must(nodes.find((n) => n.id === id));
      expect(member.position.x).toBeGreaterThan(frame.position.x);
      expect(member.position.y).toBeGreaterThan(frame.position.y);
      expect(member.position.x + DEFAULT_NODE_WIDTH).toBeLessThan(
        frame.position.x + (frame.width ?? 0)
      );
      expect(member.position.y + DEFAULT_NODE_HEIGHT).toBeLessThan(
        frame.position.y + (frame.height ?? 0)
      );
    }
    expect(
      must(
        edges.find((e) => e.source === 'lint.fail' && e.target === 'lint.fix')
      ).sourceHandle
    ).toBe('failure');
    expect(
      must(
        edges.find((e) => e.source === 'lint.fail' && e.target === 'stage:test')
      ).sourceHandle
    ).toBe('success');
  });

  it('draws every node when there is no stage view', () => {
    const { nodes } = layoutGraph(createStagedGraph(), {});
    expect(nodes).toHaveLength(7);
    expect(nodes.every((n) => n.type === 'workflowNode')).toBe(true);
  });
});
