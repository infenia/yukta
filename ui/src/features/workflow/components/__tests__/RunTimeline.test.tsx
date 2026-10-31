// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunTimeline, ViewSwitch } from '../RunTimeline';
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
    ).toEqual(['first', 'later']);
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
});
