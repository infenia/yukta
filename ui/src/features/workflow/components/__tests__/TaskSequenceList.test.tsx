// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TaskSequenceList } from '../TaskSequenceList';
import {
  createMockGraph,
  createMockProgress,
} from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';

function tasksByNode(): Record<string, TaskProgress> {
  return Object.fromEntries(
    createMockProgress().tasks.map((task) => [task.nodeId, task])
  );
}

describe('TaskSequenceList', () => {
  it('renders one item per node in topological order', () => {
    render(
      <TaskSequenceList graph={createMockGraph()} tasksByNode={tasksByNode()} />
    );

    const items = screen.getAllByTestId(/task-item-/);
    expect(items).toHaveLength(4);
    expect(items[0]).toHaveAttribute('data-testid', 'task-item-data-ingress');
    expect(items[3]).toHaveAttribute('data-testid', 'task-item-sink-storage');
  });

  it('shows status labels, module and timing per task', () => {
    render(
      <TaskSequenceList graph={createMockGraph()} tasksByNode={tasksByNode()} />
    );

    const completed = screen.getByTestId('task-item-data-ingress');
    expect(completed).toHaveTextContent('Succeeded');
    expect(completed).toHaveTextContent('module: ingress-v1');
    expect(completed).toHaveTextContent('Start: 14:22:01');
    expect(completed).toHaveTextContent('End: 14:22:10');

    const running = screen.getByTestId('task-item-vectorize-batch');
    expect(running).toHaveTextContent('Running');
    expect(running).toHaveTextContent('Dur:');
  });

  it('dims pending tasks and omits timing when not started', () => {
    render(
      <TaskSequenceList graph={createMockGraph()} tasksByNode={tasksByNode()} />
    );

    const pending = screen.getByTestId('task-item-sink-storage');
    expect(pending).toHaveTextContent('Waiting');
    expect(pending.className).toContain('opacity-60');
    expect(pending).not.toHaveTextContent('Start:');
  });

  it('falls back to all-pending entries for a never-run workflow', () => {
    render(<TaskSequenceList graph={createMockGraph()} tasksByNode={{}} />);

    const items = screen.getAllByTestId(/task-item-/);
    expect(items).toHaveLength(4);
    items.forEach((item) => expect(item).toHaveTextContent('Waiting'));
    expect(screen.getByTestId('task-item-data-ingress')).toHaveTextContent(
      'module: ingress-v1'
    );
  });

  it('falls back to node declaration order when topologicalOrder is empty', () => {
    const graph = createMockGraph({ topologicalOrder: [] });
    render(<TaskSequenceList graph={graph} tasksByNode={{}} />);
    expect(screen.getAllByTestId(/task-item-/)).toHaveLength(4);
  });

  it('labels unknown nodes in the order with an unknown module', () => {
    const graph = createMockGraph({
      topologicalOrder: [
        'ghost-node',
        'data-ingress',
        'vectorize-batch',
        'anomaly-detection',
      ],
    });
    render(<TaskSequenceList graph={graph} tasksByNode={{}} />);
    expect(screen.getByTestId('task-item-ghost-node')).toHaveTextContent(
      'module: unknown'
    );
  });

  it('refreshes the running task duration on an interval without new SSE data', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-07-26T14:22:12'));
      const tasks = tasksByNode();
      tasks['vectorize-batch'] = {
        ...tasks['vectorize-batch'],
        startTime: '2026-07-26T14:22:10',
      };
      render(
        <TaskSequenceList graph={createMockGraph()} tasksByNode={tasks} />
      );

      const running = screen.getByTestId('task-item-vectorize-batch');
      expect(running).toHaveTextContent('Dur: 2s');

      act(() => {
        vi.setSystemTime(new Date('2026-07-26T14:22:20'));
        vi.advanceTimersByTime(1000);
      });

      expect(running).toHaveTextContent('Dur: 11s');
    } finally {
      vi.useRealTimers();
    }
  });

  it('marks failed tasks with the Failed label and destructive styling', () => {
    const tasks = tasksByNode();
    tasks['vectorize-batch'] = {
      ...tasks['vectorize-batch'],
      status: 'FAILURE',
    };
    render(<TaskSequenceList graph={createMockGraph()} tasksByNode={tasks} />);

    const failed = screen.getByTestId('task-item-vectorize-batch');
    expect(failed).toHaveTextContent('Failed');
    expect(failed.querySelector('.text-destructive')).not.toBeNull();
  });

  it('summarizes the run and filters nodes by status', async () => {
    const user = userEvent.setup();
    render(
      <TaskSequenceList graph={createMockGraph()} tasksByNode={tasksByNode()} />
    );

    expect(screen.getByText('4 nodes')).toBeInTheDocument();
    expect(screen.getByRole('img')).toHaveAttribute(
      'aria-label',
      '1 Running, 1 Succeeded, 2 Waiting'
    );

    await user.click(screen.getByRole('button', { name: 'Running 1' }));
    expect(screen.getAllByTestId(/task-item-/)).toHaveLength(1);
    expect(screen.getByTestId('task-item-vectorize-batch')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Running 1' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );

    await user.click(screen.getByRole('button', { name: 'All 4' }));
    expect(screen.getAllByTestId(/task-item-/)).toHaveLength(4);
  });

  it('finds nodes by ID or module, and says when none match', async () => {
    const user = userEvent.setup();
    render(
      <TaskSequenceList graph={createMockGraph()} tasksByNode={tasksByNode()} />
    );

    await user.type(
      screen.getByRole('searchbox', { name: 'Filter nodes' }),
      'SINK'
    );
    expect(screen.getAllByTestId(/task-item-/)).toHaveLength(1);

    await user.clear(screen.getByRole('searchbox'));
    await user.type(screen.getByRole('searchbox'), 'ingress-v1');
    expect(screen.getByTestId('task-item-data-ingress')).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox'), 'zzz');
    expect(screen.getByText('No nodes match.')).toBeInTheDocument();
    expect(screen.queryAllByTestId(/task-item-/)).toHaveLength(0);
  });

  it('draws an empty bar for a workflow without nodes', () => {
    render(
      <TaskSequenceList
        graph={createMockGraph({ nodes: [], edges: [], topologicalOrder: [] })}
        tasksByNode={{}}
      />
    );
    expect(screen.getByText('0 nodes')).toBeInTheDocument();
    expect(screen.getByRole('img')).toHaveAttribute('aria-label', '');
  });
});
