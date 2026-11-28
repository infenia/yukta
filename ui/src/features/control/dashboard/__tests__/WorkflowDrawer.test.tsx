// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import {
  useDashboardParams,
  useMetricSeries,
  useNodes,
} from '@/features/metrics/hooks';
import { WorkflowDrawer } from '../WorkflowDrawer';

vi.mock('@/features/metrics/hooks', () => ({
  useDashboardParams: vi.fn(),
  useMetricSeries: vi.fn(),
  useNodes: vi.fn(),
}));
// Recharts is exercised by the end-to-end specs; here the chart is a stand-in.
vi.mock('@/features/metrics/SeriesChart', () => ({
  default: ({
    points,
    label,
    format,
  }: {
    points: unknown[];
    label: string;
    format: (v: number) => string;
  }) => (
    <p>
      chart:{label}:{points.length}:{format(1500)}
    </p>
  ),
}));

const setScope = vi.fn();
const series = vi.mocked(useMetricSeries);
const nodes = vi.mocked(useNodes);
const ok = (data: unknown) =>
  ({ isPending: false, error: null, data, refetch: vi.fn() }) as never;

const workflow = {
  workflowId: 'wf-a',
  runs: 12,
  failures: 2,
  p50Ms: 1000,
  p95Ms: 4000,
};

const node = (nodeId: string, over: Record<string, unknown> = {}) => ({
  sessionId: 's 1',
  workflowId: 'wf-a',
  nodeId,
  module: 'echo',
  runs: 5,
  failures: 0,
  p50Ms: 20,
  p95Ms: 30,
  lastExecutionId: 'e/1',
  ...over,
});

describe('WorkflowDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useDashboardParams).mockReturnValue({ setScope } as never);
    series.mockReturnValue(
      ok({
        'duration.p95': [
          { t: 1, v: 1000 },
          { t: 2, v: 2000 },
        ],
      })
    );
    nodes.mockReturnValue(
      ok([node('build', { failures: 1 }), node('idle', { p50Ms: 0, p95Ms: 0 })])
    );
  });

  it("shows the workflow's numbers, its run time over the range and its nodes", async () => {
    renderWithProviders(
      <WorkflowDrawer workflow={workflow} range="6h" onClose={vi.fn()} />
    );

    expect(screen.getByRole('dialog', { name: 'wf-a' })).toBeInTheDocument();
    expect(
      screen.getByText('Runs', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('12');
    expect(
      screen.getByText('Failed', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('2');
    expect(
      screen.getByText('p50', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('1.0 s');
    expect(
      screen.getByText('p95', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('4.0 s');
    expect(
      await screen.findByText('chart:Run time, p95:2:1.5 s')
    ).toBeInTheDocument();
    expect(series).toHaveBeenCalledWith('duration.', '6h', 'workflow:wf-a');
    expect(nodes).toHaveBeenCalledWith('6h', 'workflow:wf-a', false);

    expect(screen.getByRole('link', { name: 'build' })).toHaveAttribute(
      'href',
      '/sessions/s%201/workflow/wf-a?exec=e%2F1&node=build'
    );
    // a node that was never timed shows dashes, and one that never failed none
    const idle = screen.getByRole('link', { name: 'idle' }).closest('tr');
    expect(idle).toHaveTextContent('–');
  });

  it('shows dashes for run times that were not measured, and says there is too little to chart', () => {
    series.mockReturnValue(ok({}));
    renderWithProviders(
      <WorkflowDrawer
        workflow={{ ...workflow, p50Ms: 0, p95Ms: 0 }}
        range="1h"
        onClose={vi.fn()}
      />
    );
    expect(
      screen.getByText('p50', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('–');
    expect(
      screen.getByText('p95', { selector: 'dt' }).nextSibling
    ).toHaveTextContent('–');
    expect(
      screen.getByText('Not enough runs to chart yet')
    ).toBeInTheDocument();
  });

  it('says so when no node was timed, and retries a failed read', () => {
    nodes.mockReturnValue(ok([]));
    const { unmount } = renderWithProviders(
      <WorkflowDrawer workflow={workflow} range="1h" onClose={vi.fn()} />
    );
    expect(
      screen.getByText('No node timings in this range')
    ).toBeInTheDocument();
    unmount();

    const refetch = vi.fn();
    nodes.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(
      <WorkflowDrawer workflow={workflow} range="1h" onClose={vi.fn()} />
    );
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });

  it('focuses the dashboard on the workflow and closes', () => {
    const onClose = vi.fn();
    renderWithProviders(
      <WorkflowDrawer workflow={workflow} range="1h" onClose={onClose} />
    );
    fireEvent.click(
      screen.getByRole('button', {
        name: 'Focus the dashboard on this workflow',
      })
    );
    expect(setScope).toHaveBeenCalledWith('workflow:wf-a');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes from the Close button and from Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <WorkflowDrawer workflow={workflow} range="1h" onClose={onClose} />
    );
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
    expect(setScope).not.toHaveBeenCalled();
  });
});
