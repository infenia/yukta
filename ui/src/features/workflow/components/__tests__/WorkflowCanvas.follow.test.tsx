// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render } from '@testing-library/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import {
  createMockGraph,
  createMockTask,
} from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';

const fitView = vi.fn(() => Promise.resolve(true));
const getNode = vi.fn<
  (id: string) => { measured?: { width?: number } } | undefined
>(() => ({ measured: { width: 100, height: 40 } }));
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  useReactFlow: () => ({ fitView, getNode }),
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

describe('WorkflowCanvas centerOn', () => {
  beforeEach(() => {
    fitView.mockClear();
    vi.useFakeTimers();
  });
  afterEach(() => vi.useRealTimers());

  it('centres the picked node once the side panel has settled', () => {
    const graph = createMockGraph();
    const { rerender } = render(
      <WorkflowCanvas graph={graph} tasksByNode={{}} />
    );
    expect(fitView).not.toHaveBeenCalled();

    rerender(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={{}}
        centerOn={{ id: 'sink-storage', nonce: 1 }}
      />
    );
    expect(fitView).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(160);
    });
    expect(fitView).toHaveBeenLastCalledWith(
      expect.objectContaining({ nodes: [{ id: 'sink-storage' }] })
    );
  });
});

describe('WorkflowCanvas centerOn waits for the node', () => {
  const measured = { measured: { width: 100, height: 40 } };
  const pick = (rerender: ReturnType<typeof render>['rerender']) =>
    rerender(
      <WorkflowCanvas
        graph={createMockGraph()}
        tasksByNode={{}}
        centerOn={{ id: 'sink-storage', nonce: 1 }}
      />
    );

  beforeEach(() => {
    fitView.mockClear();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    getNode.mockImplementation(() => measured);
  });

  it('waits while the node is not in the layout yet', () => {
    getNode.mockImplementation(() => undefined);
    const { rerender } = render(
      <WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />
    );
    pick(rerender);
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(fitView).not.toHaveBeenCalled();

    getNode.mockImplementation(() => measured);
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(fitView).toHaveBeenCalledWith(
      expect.objectContaining({ nodes: [{ id: 'sink-storage' }] })
    );
  });

  it('waits while the node is not measured yet', () => {
    getNode.mockImplementation(() => ({ measured: {} }));
    const { rerender } = render(
      <WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />
    );
    pick(rerender);
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(fitView).not.toHaveBeenCalled();

    getNode.mockImplementation(() => measured);
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(fitView).toHaveBeenCalledTimes(1);
  });

  it('gives up waiting after twenty ticks and centres anyway', () => {
    getNode.mockImplementation(() => undefined);
    const { rerender } = render(
      <WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />
    );
    pick(rerender);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(fitView).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(fitView).toHaveBeenCalledTimes(1);
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
