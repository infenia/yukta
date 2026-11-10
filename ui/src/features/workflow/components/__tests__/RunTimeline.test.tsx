// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { RunTimeline, ViewSwitch } from '../RunTimeline';
import { SubWorkflowLinks } from '../../hooks/useSubWorkflowLinks';
import {
  createMockGraph,
  createMockGraphNode,
} from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';

const task = (
  nodeId: string,
  status: string,
  startTime: string | null,
  endTime: string | null
): TaskProgress => ({ nodeId, module: 'M', status, startTime, endTime });

afterEach(() => vi.useRealTimers());

describe('RunTimeline', () => {
  it('is a table with a row per started node, in start order', () => {
    render(
      <RunTimeline
        switcher={null}
        tasks={[
          task(
            'later',
            'FAILURE',
            '2026-01-01T10:00:05',
            '2026-01-01T10:00:09'
          ),
          task(
            'first',
            'SUCCESS',
            '2026-01-01T10:00:00',
            '2026-01-01T10:00:05'
          ),
          task('waiting', 'PENDING', null, null),
        ]}
      />
    );
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(
      rows.map((r) => within(r).getAllByRole('rowheader')[0].textContent)
    ).toEqual(['firstM', 'laterM']);
    expect(within(rows[0]).getByText('5s')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-bar-later').className).toContain(
      'bg-destructive'
    );
  });

  it('says so before any node has started', () => {
    render(<RunTimeline switcher={null} />);
    expect(screen.getByText('No node has started yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('grows the bar of a running node each second', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:10'));
    render(
      <RunTimeline
        switcher={null}
        tasks={[
          task('done', 'SUCCESS', '2026-01-01T10:00:00', '2026-01-01T10:00:10'),
          task('busy', 'RUNNING', '2026-01-01T10:00:05', null),
        ]}
      />
    );
    const width = () => screen.getByTestId('timeline-bar-busy').style.width;
    const before = width();
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(width()).not.toBe(before);
  });

  it('stops ticking when nothing is running', () => {
    vi.useFakeTimers();
    const clear = vi.spyOn(globalThis, 'clearInterval');
    const { unmount } = render(
      <RunTimeline
        switcher={null}
        tasks={[task('a', 'RUNNING', '2026-01-01T10:00:00', null)]}
      />
    );
    unmount();
    expect(clear).toHaveBeenCalled();
  });
});

describe('ViewSwitch', () => {
  it('marks the current view and reports a change', async () => {
    const onChange = vi.fn();
    render(<ViewSwitch view="graph" onChange={onChange} />);
    expect(screen.getByRole('radio', { name: 'Graph' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: 'Timeline' }));
    expect(onChange).toHaveBeenCalledWith('timeline');
  });

  describe('with the workflow', () => {
    const tasks = [
      task('lint.a', 'SUCCESS', '2026-01-01T10:00:00', '2026-01-01T10:00:02'),
      task('lint.b', 'SUCCESS', '2026-01-01T10:00:02', '2026-01-01T10:00:04'),
      task('deploy', 'SUCCESS', '2026-01-01T10:00:04', '2026-01-01T10:00:06'),
    ];
    const graph = createMockGraph({
      nodes: [
        createMockGraphNode({ nodeId: 'lint.a', type: 'SHELL' }),
        createMockGraphNode({ nodeId: 'lint.b', type: 'SHELL' }),
        createMockGraphNode({
          nodeId: 'deploy',
          type: 'SUB_WORKFLOW',
          subWorkflowId: 'release',
        }),
      ],
    });
    const renderTimeline = () =>
      render(
        <MemoryRouter>
          <SubWorkflowLinks
            value={new Map([['deploy', '/sessions/s/workflow/release']])}
          >
            <RunTimeline switcher={null} tasks={tasks} graph={graph} />
          </SubWorkflowLinks>
        </MemoryRouter>
      );

    it('groups the nodes of a stage under a header that folds them away', async () => {
      renderTimeline();
      expect(screen.getAllByText('SHELL')).toHaveLength(2);
      await userEvent.click(
        screen.getByRole('button', { name: 'Collapse stage lint' })
      );
      expect(screen.queryByText('lint.a')).not.toBeInTheDocument();
      expect(screen.getByText('deploy')).toBeInTheDocument();
      await userEvent.click(
        screen.getByRole('button', { name: 'Expand stage lint' })
      );
      expect(screen.getByText('lint.a')).toBeInTheDocument();
    });

    it('shows the node type and links a sub-workflow node to its page', () => {
      renderTimeline();
      expect(screen.getByText('SUB_WORKFLOW')).toBeInTheDocument();
      expect(
        screen.getByRole('link', { name: 'Open sub-workflow of deploy' })
      ).toHaveAttribute('href', '/sessions/s/workflow/release');
    });
  });
});
