// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import {
  createMockGraph,
  createMockGraphNode,
} from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';
import { WorkflowCanvas } from '../WorkflowCanvas';

// Capture what the canvas asks xyflow to fit on open
const fitted: { current: unknown } = { current: undefined };
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  ReactFlow: (props: { fitViewOptions?: unknown }) => {
    fitted.current = props.fitViewOptions;
    return null;
  },
}));

const graphOf = (count: number) => {
  const ids = Array.from({ length: count }, (_, i) => `n${i}`);
  return createMockGraph({
    nodes: ids.map((nodeId) => createMockGraphNode({ nodeId })),
    edges: ids.slice(1).map((target, i) => ({
      source: ids[i],
      target,
      sourcePort: null,
    })),
    topologicalOrder: ids,
  });
};

const ids = () =>
  (fitted.current as { nodes?: { id: string }[] }).nodes?.map((n) => n.id);

describe('WorkflowCanvas opening view', () => {
  beforeEach(() => {
    fitted.current = undefined;
  });

  it('fits a small graph whole', () => {
    render(<WorkflowCanvas graph={graphOf(30)} tasksByNode={{}} />);
    expect(ids()).toBeUndefined();
  });

  it('opens a large graph on its first nodes when nothing runs', () => {
    render(<WorkflowCanvas graph={graphOf(31)} tasksByNode={{}} />);
    expect(ids()).toHaveLength(12);
    expect(ids()?.[0]).toBe('n0');
  });

  it('opens a large graph on its running nodes', () => {
    const tasks: Record<string, TaskProgress> = {
      n20: {
        nodeId: 'n20',
        module: 'x',
        status: 'RUNNING',
        startTime: null,
        endTime: null,
      },
    };
    render(<WorkflowCanvas graph={graphOf(40)} tasksByNode={tasks} />);
    expect(ids()).toEqual(['n20']);
  });
});
