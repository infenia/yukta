// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useHeatmap, useMetricSnapshot } from '@/features/metrics/hooks';
import { getLimits } from '../../api/scheduler';
import { getOpenInputs } from '@/features/workflow/api/inputs';
import { CommandStrip } from '../CommandStrip';

vi.mock('@/features/metrics/hooks', () => ({
  useMetricSnapshot: vi.fn(),
  useMetricSeries: (name: string) => ({
    data:
      name === 'yukta.executions.'
        ? {
            'yukta.executions.running': [
              { t: 1, v: 1 },
              { t: 2, v: 3 },
            ],
            'yukta.executions.queued': [
              { t: 1, v: 0 },
              { t: 2, v: 2 },
            ],
          }
        : {
            'overhead.start.p95': [
              { t: 1, v: 2 },
              { t: 2, v: 4 },
            ],
          },
  }),
  useHeatmap: vi.fn(),
}));
vi.mock('../MetricDrawer', () => ({
  MetricDrawer: ({
    spec,
    onClose,
  }: {
    spec: { title: string; points: unknown[]; format?: (v: number) => string };
    onClose: () => void;
  }) => (
    <div>
      <p>
        drawer:{spec.title}:{spec.points.length}:{spec.format?.(5) ?? 'plain'}
      </p>
      <button onClick={onClose}>close-drawer</button>
    </div>
  ),
}));
vi.mock('../../api/scheduler', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/scheduler')>()),
  getLimits: vi.fn(),
}));
vi.mock('@/features/workflow/api/inputs', () => ({ getOpenInputs: vi.fn() }));

const heatmap = vi.mocked(useHeatmap);
const snapshot = vi.mocked(useMetricSnapshot);
const limits = vi.mocked(getLimits);
const approvals = vi.mocked(getOpenInputs);

const asHeat = (data: object[]) =>
  ({ data }) as unknown as ReturnType<typeof useHeatmap>;
const asSnapshot = (data: Record<string, number>) =>
  ({ data }) as unknown as ReturnType<typeof useMetricSnapshot>;

beforeEach(() => {
  heatmap.mockReturnValue(asHeat([]));
  snapshot.mockReturnValue(asSnapshot({}));
  limits.mockResolvedValue({
    running: 2,
    queued: 1,
    maxConcurrent: 0,
    maxPerWorkflow: 0,
    queueCapacity: 100,
  } as Awaited<ReturnType<typeof getLimits>>);
  approvals.mockResolvedValue([]);
});

describe('CommandStrip', () => {
  it('shows what runs and waits, with the limit and the queue size', async () => {
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(await screen.findByText('of unlimited')).toBeInTheDocument();
    expect(screen.getByText('holds 100')).toBeInTheDocument();
    expect(screen.getByText('Running')).toBeInTheDocument();
  });

  it('shows dashes until the limits arrive', () => {
    limits.mockReturnValue(new Promise(() => undefined));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getAllByText('–').length).toBeGreaterThanOrEqual(2);
  });

  it('says the scheduler tiles read the whole server when the view is scoped', async () => {
    renderWithProviders(<CommandStrip scope="workflow:w" live={false} />);
    expect(
      await screen.findByText('Running (whole server)')
    ).toBeInTheDocument();
    expect(screen.getByText('Queued (whole server)')).toBeInTheDocument();
  });

  it('shows the engine overhead and what it is not', async () => {
    snapshot.mockReturnValue(
      asSnapshot({ 'overhead.start.p95': 12, 'overhead.handoff.p95': 4 })
    );
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getByText('12 ms')).toBeInTheDocument();
    expect(
      screen.getByText('request to plugin · hand-off 4 ms')
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: 'About Engine p95 (1 h)' })
    );
    expect(await screen.findByText(/leaves out the plugin/)).toBeVisible();
  });

  it('has no engine number before anything was measured', () => {
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getByText('request to first plugin')).toBeInTheDocument();
  });

  it('counts the failed runs of the hour out of the finished ones', () => {
    snapshot.mockReturnValue(
      asSnapshot({ 'runs.failed': 3, 'runs.finished': 40 })
    );
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getByText('of 40 runs')).toBeInTheDocument();
  });
});

describe('CommandStrip success rate', () => {
  it('shows the success rate with the day’s run count when runs failed', () => {
    heatmap.mockReturnValue(asHeat([{ runs: 10, failed: 5 }]));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getAllByText('50%').length).toBeGreaterThan(0);
    expect(screen.getByText('of 10 runs')).toBeInTheDocument();
  });

  it('shows a dash with no runs in the day', () => {
    renderWithProviders(<CommandStrip scope="global" live />);
    expect(screen.getByText('Success (24 h)')).toBeInTheDocument();
  });

  it('shows the success rate with the day’s run count when nearly all succeeded', () => {
    heatmap.mockReturnValue(asHeat([{ runs: 100, failed: 1 }]));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getAllByText('99%').length).toBeGreaterThan(0);
  });
});

describe('CommandStrip inputs tile', () => {
  const wait = (i: number) => ({
    sessionId: 's1',
    workflowId: 'wf',
    executionId: 'e',
    nodeId: `n${i}`,
    name: 'approve',
    since: 't',
  });

  it('links to the inbox with the count', async () => {
    approvals.mockResolvedValue([wait(1), wait(2)]);
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    const link = await screen.findByRole('link', {
      name: /Inputs waiting: 2\./,
    });
    expect(link).toHaveAttribute('href', '/inbox');
  });

  it('says there are more when a full page waits', async () => {
    approvals.mockResolvedValue(Array.from({ length: 50 }, (_, i) => wait(i)));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(
      await screen.findByRole('link', { name: /Inputs waiting: 50\+\./ })
    ).toBeInTheDocument();
  });

  it('shows a dash when the inputs cannot be read', async () => {
    approvals.mockRejectedValue(new Error('down'));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(
      await screen.findByRole('link', { name: /Inputs waiting: –\./ })
    ).toBeInTheDocument();
  });
});

describe('CommandStrip drill-down', () => {
  const MIN = 60_000;
  const open = async (label: string) => {
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    await userEvent.click(
      await screen.findByRole('button', { name: `Open ${label} chart` })
    );
  };

  it('opens the running history as a larger chart and closes it again', async () => {
    await open('Running');
    expect(screen.getByText('drawer:Running:2:plain')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'close-drawer' }));
    expect(screen.queryByText(/drawer:/)).toBeNull();
  });

  it('opens the queue history', async () => {
    await open('Queued');
    expect(screen.getByText('drawer:Queued:2:plain')).toBeInTheDocument();
  });

  it('opens the engine p95 history in milliseconds', async () => {
    await open('Engine p95 (1 h)');
    expect(screen.getByText('drawer:Engine p95:2:5 ms')).toBeInTheDocument();
  });

  it('opens the success rate by half hour, in percent', async () => {
    const t = Math.floor(Date.now() / (30 * MIN)) * 30 * MIN;
    heatmap.mockReturnValue(
      asHeat([
        { time: t - 30 * MIN, runs: 10, failed: 5 },
        { time: t, runs: 10, failed: 0 },
      ])
    );
    await open('Success (24 h)');
    expect(screen.getByText('drawer:Success rate:2:5%')).toBeInTheDocument();
  });

  it('opens the failed runs of the last hour with a point per five minutes', async () => {
    heatmap.mockReturnValue(asHeat([{ time: Date.now(), runs: 3, failed: 2 }]));
    await open('Failed (1 h)');
    expect(screen.getByText('drawer:Failed runs:12:plain')).toBeInTheDocument();
  });

  it('draws no sparkline, so offers no chart, for a success rate with one point', () => {
    heatmap.mockReturnValue(asHeat([{ time: Date.now(), runs: 3, failed: 2 }]));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(
      screen.queryByRole('button', { name: 'Open Success (24 h) chart' })
    ).toBeNull();
  });

  it('explains each tile behind its info button', async () => {
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    await userEvent.click(
      screen.getByRole('button', { name: 'About Failed (1 h)' })
    );
    expect(await screen.findByText(/failed in the last hour/)).toBeVisible();
  });
});
