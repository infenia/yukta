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
  useMetricSeries: () => ({
    data: {
      'overhead.start.p95': [
        { t: 1, v: 2 },
        { t: 2, v: 4 },
      ],
    },
  }),
  useHeatmap: vi.fn(),
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
    await userEvent.hover(
      screen.getByText('Engine p95 (1 h)').parentElement as HTMLElement
    );
    expect(await screen.findByText(/leaves out the plugin/)).toHaveAttribute(
      'data-slot',
      'tooltip-content'
    );
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
  it('shows a danger ring when fewer than 90% of the day’s runs succeeded', () => {
    heatmap.mockReturnValue(asHeat([{ runs: 10, failed: 5 }]));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getAllByText('50%').length).toBeGreaterThan(0);
  });

  it('shows a dash with no runs in the day', () => {
    renderWithProviders(<CommandStrip scope="global" live />);
    expect(screen.getByText('Success (24 h)')).toBeInTheDocument();
  });

  it('shows a success ring when nearly all runs succeeded', () => {
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
