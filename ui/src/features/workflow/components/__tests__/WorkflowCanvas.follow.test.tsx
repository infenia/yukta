// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import {
  createMockGraph,
  createMockTask,
} from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';

const fitView = vi.fn(() => Promise.resolve(true));
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  useReactFlow: () => ({ fitView }),
}));

const running = (...ids: string[]): Record<string, TaskProgress> =>
  Object.fromEntries(
    ids.map((nodeId) => [nodeId, createMockTask({ nodeId, status: 'RUNNING' })])
  );

describe('WorkflowCanvas follow', () => {
  beforeEach(() => fitView.mockClear());

  it('fits to the running nodes, again whenever they change', () => {
    const graph = createMockGraph();
    const { rerender } = render(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={running('vectorize-batch')}
        follow
      />
    );
    expect(fitView).toHaveBeenLastCalledWith(
      expect.objectContaining({ nodes: [{ id: 'vectorize-batch' }] })
    );

    rerender(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={running('anomaly-detection', 'sink-storage')}
        follow
      />
    );
    expect(fitView).toHaveBeenLastCalledWith(
      expect.objectContaining({
        nodes: [{ id: 'anomaly-detection' }, { id: 'sink-storage' }],
      })
    );
    const calls = fitView.mock.calls.length;

    rerender(<WorkflowCanvas graph={graph} tasksByNode={{}} follow />);
    expect(fitView).toHaveBeenCalledTimes(calls);
  });

  it('leaves the view alone when not following', () => {
    render(
      <WorkflowCanvas
        graph={createMockGraph()}
        tasksByNode={running('vectorize-batch')}
      />
    );
    expect(fitView).not.toHaveBeenCalled();
  });
});

describe('WorkflowCanvas find', () => {
  beforeEach(() => fitView.mockClear());

  it('brings the nodes whose ID matches into view, over following', () => {
    const graph = createMockGraph();
    const { rerender } = render(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={running('sink-storage')}
        follow
        find=" VECTOR "
      />
    );
    expect(fitView).toHaveBeenLastCalledWith(
      expect.objectContaining({ nodes: [{ id: 'vectorize-batch' }] })
    );

    // cleared: following takes over again
    rerender(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={running('sink-storage')}
        follow
        find=""
      />
    );
    expect(fitView).toHaveBeenLastCalledWith(
      expect.objectContaining({ nodes: [{ id: 'sink-storage' }] })
    );
  });

  it('does nothing when no node matches', () => {
    render(
      <WorkflowCanvas
        graph={createMockGraph()}
        tasksByNode={{}}
        find="no-such-node"
      />
    );
    expect(fitView).not.toHaveBeenCalled();
  });
});
