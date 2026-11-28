// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import {
  useAnnotations,
  useDashboardParams,
  useHeatmap,
  useMetricSeries,
} from '@/features/metrics/hooks';
import { ThroughputPanel, OverheadPanel } from '../ChartPanels';

const nav = vi.hoisted(() => ({ to: vi.fn() }));

vi.mock('react-router', async (orig) => ({
  ...(await orig<typeof import('react-router')>()),
  useNavigate: () => nav.to,
}));
vi.mock('@/features/metrics/hooks', () => ({
  useMetricSeries: vi.fn(),
  useAnnotations: vi.fn(),
  useHeatmap: vi.fn(),
  useDashboardParams: vi.fn(),
}));
vi.mock('../useNow', () => ({ useNow: () => 1_000_000 }));
vi.mock('../RunsChart', () => ({
  default: () => <div>runs-chart</div>,
}));
vi.mock('../ApiChart', () => ({
  default: ({ series }: { series: Record<string, unknown[]> }) => (
    <div>api-chart:{Object.keys(series).length}</div>
  ),
}));
vi.mock('../OverheadChart', () => ({
  default: ({ label }: { label?: string }) => (
    <div data-label={label ?? 'default'}>overhead-chart</div>
  ),
}));
vi.mock('../RunPanels', () => ({
  DayHeat: () => <div>day-heat</div>,
}));
vi.mock('@/components/dashboard/Strips', () => ({
  ChartAnnotations: ({
    items,
    onSelect,
  }: {
    items: { at: number; label: string; kind: string }[];
    onSelect: (item: { at: number }) => void;
  }) => (
    <div>
      {items.map((item) => (
        <button key={item.at} onClick={() => onSelect(item)}>
          {item.label}|{item.kind}
        </button>
      ))}
      <button onClick={() => onSelect({ at: -1 })}>unknown-mark</button>
    </div>
  ),
}));

const series = vi.mocked(useMetricSeries);
const annotations = vi.mocked(useAnnotations);
const heat = vi.mocked(useHeatmap);
const setRange = vi.fn();

beforeEach(() => {
  heat.mockReturnValue(ok([]));
  vi.mocked(useDashboardParams).mockReturnValue({ setRange } as never);
});
const ok = (data: unknown) =>
  ({ isPending: false, error: null, data, refetch: vi.fn() }) as never;

describe('ThroughputPanel', () => {
  beforeEach(() => {
    nav.to.mockReset();
    heat.mockReturnValue(ok([]));
    vi.mocked(useDashboardParams).mockReturnValue({ setRange } as never);
    setRange.mockReset();
    series.mockReturnValue(ok({ 'runs.finished{status=ok}': [] }));
  });

  it('has the day band under the chart', () => {
    annotations.mockReturnValue(ok([]) as never);
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    expect(screen.getByText('day-heat')).toBeInTheDocument();
  });

  it('labels marks by command, actor and whether they were applied', () => {
    annotations.mockReturnValue(
      ok([
        { time: 1, command: 'pause', actor: 'ops', applied: true },
        { time: 2, command: 'stop', applied: false },
      ]) as never
    );
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    expect(screen.getByText('pause by ops|attention')).toBeInTheDocument();
    expect(screen.getByText('stop (refused)|danger')).toBeInTheDocument();
  });

  it('opens the workflow of a mark that has one, and the audit otherwise', () => {
    annotations.mockReturnValue(
      ok([
        {
          time: 1,
          command: 'pause',
          applied: true,
          sessionId: 's1',
          workflowId: 'w1',
        },
        { time: 2, command: 'stop', applied: true },
      ]) as never
    );
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByText(/pause/));
    expect(nav.to).toHaveBeenCalledWith('/sessions/s1/workflow/w1');
    // a command that names no run leads to the control audit
    fireEvent.click(screen.getByText(/stop/));
    expect(nav.to).toHaveBeenLastCalledWith('/control?tab=audit');
  });
});

describe('OverheadPanel', () => {
  it('draws the engine timings', async () => {
    series.mockReturnValue(ok({ 'overhead.handoff.p95': [{ t: 1, v: 2 }] }));
    renderWithProviders(<OverheadPanel scope="global" range="1h" live />);
    expect(await screen.findByText('overhead-chart')).toBeInTheDocument();
    expect(series).toHaveBeenCalledWith('overhead.', '1h', 'global');
  });

  it('says in one line that nothing was timed in the range', () => {
    series.mockReturnValue(ok({}));
    renderWithProviders(<OverheadPanel scope="global" range="1h" live />);
    expect(
      screen.getByText('No engine timings in this range')
    ).toBeInTheDocument();
  });

  it('retries the timings when they fail to load', () => {
    const refetch = vi.fn();
    series.mockReturnValue({
      isPending: false,
      error: new Error('slow'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(<OverheadPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('ThroughputPanel failures', () => {
  it('retries the runs when they fail to load', () => {
    const refetch = vi.fn();
    series.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch,
    } as never);
    annotations.mockReturnValue(ok([]) as never);
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('ThroughputPanel unknown mark', () => {
  beforeEach(() => {
    nav.to.mockReset();
    series.mockReturnValue(ok({ 'runs.finished{status=ok}': [] }));
  });

  it('opens nothing for a mark that is not in the list', () => {
    annotations.mockReturnValue(
      ok([
        {
          time: 1,
          command: 'pause',
          applied: true,
          sessionId: 's1',
          workflowId: 'w1',
        },
      ]) as never
    );
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'unknown-mark' }));
    expect(nav.to).not.toHaveBeenCalled();
  });

  it('opens nothing before the marks have loaded', () => {
    annotations.mockReturnValue(ok(undefined) as never);
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'unknown-mark' }));
    expect(nav.to).not.toHaveBeenCalled();
  });
});

describe('ThroughputPanel API calls', () => {
  const apiSeries = {
    'yukta.api.requests{op=read}|rate': [{ t: 1, v: 2 }],
    'yukta.api.requests{op=read}|mean': [
      { t: 1, v: 10 },
      { t: 2, v: 20 },
    ],
    'yukta.api.requests{op=run}|mean': [],
  };

  beforeEach(() => {
    heat.mockReturnValue(ok([]));
    vi.mocked(useDashboardParams).mockReturnValue({ setRange } as never);
    annotations.mockReturnValue(ok([]) as never);
    series.mockReturnValue(ok(apiSeries));
  });

  it('switches to the REST calls by kind, with the latest mean time per call', async () => {
    renderWithProviders(<ThroughputPanel scope="session:s1" range="6h" live />);
    expect(screen.getByRole('button', { name: 'Runs' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );

    fireEvent.click(screen.getByRole('button', { name: 'API calls' }));

    expect(await screen.findByText('api-chart:3')).toBeInTheDocument();
    expect(
      screen.getByText('Mean time per call: read 20 ms')
    ).toBeInTheDocument();
    expect(screen.queryByText('day-heat')).toBeNull();
    // the REST calls are the whole server's, whatever scope the page is in
    expect(series).toHaveBeenCalledWith('yukta.api.requests', '6h');
    fireEvent.click(screen.getByRole('button', { name: 'Runs' }));
    expect(screen.getByText('day-heat')).toBeInTheDocument();
  });

  it('says so when no call was served in the range, and gives no mean time then', () => {
    series.mockReturnValue(ok({}));
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'API calls' }));
    expect(screen.getByText('No API calls in this range')).toBeInTheDocument();
    expect(screen.queryByText(/Mean time per call/)).toBeNull();
  });

  it('shows the chart without a mean time when only rates were sampled', async () => {
    series.mockReturnValue(
      ok({ 'yukta.api.requests{op=run}|rate': [{ t: 1, v: 1 }] })
    );
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'API calls' }));
    expect(await screen.findByText('api-chart:1')).toBeInTheDocument();
    expect(screen.queryByText(/Mean time per call/)).toBeNull();
  });

  it('retries the calls when they fail to load', () => {
    const refetch = vi.fn();
    series.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'API calls' }));
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('ThroughputPanel with no runs in the range', () => {
  beforeEach(() => {
    setRange.mockReset();
    vi.mocked(useDashboardParams).mockReturnValue({ setRange } as never);
    annotations.mockReturnValue(ok([]) as never);
    series.mockReturnValue(ok({}));
  });

  it('says when the last run finished and offers the whole day', () => {
    heat.mockReturnValue(ok([{ time: 1_000_000 - 3 * 3_600_000, runs: 2 }]));
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    expect(
      screen.getByText(/The last run finished 3 h ago\./)
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show 24 h' }));
    expect(setRange).toHaveBeenCalledWith('24h');
  });

  it('does not offer the day when it is already shown', () => {
    heat.mockReturnValue(ok([{ time: 1_000_000 - 60_000, runs: 1 }]));
    renderWithProviders(<ThroughputPanel scope="global" range="24h" live />);
    expect(
      screen.getByText(/The last run finished 1 min ago\./)
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show 24 h' })).toBeNull();
  });

  it('says nothing about a last run when the day had none, or while loading', () => {
    heat.mockReturnValue(ok([]));
    const { unmount } = renderWithProviders(
      <ThroughputPanel scope="global" range="1h" live />
    );
    expect(screen.queryByText(/last run finished/)).toBeNull();
    unmount();

    heat.mockReturnValue(ok([{ time: 1, runs: 1 }]));
    series.mockReturnValue({
      isPending: true,
      error: null,
      data: undefined,
      refetch: vi.fn(),
    } as never);
    renderWithProviders(<ThroughputPanel scope="global" range="1h" live />);
    expect(screen.queryByText(/last run finished/)).toBeNull();
  });
});

describe('OverheadPanel run time view', () => {
  beforeEach(() => {
    series.mockImplementation(((name: string) =>
      ok(
        name === 'duration.'
          ? { 'duration.p95': [{ t: 1, v: 2 }] }
          : { 'overhead.start.p95': [{ t: 1, v: 2 }] }
      )) as never);
  });

  it('switches from the engine to how long whole runs take', async () => {
    renderWithProviders(<OverheadPanel scope="workflow:w" range="6h" live />);
    expect((await screen.findByText('overhead-chart')).dataset.label).toBe(
      'default'
    );
    expect(
      screen.getByRole('heading', { name: 'Engine overhead' })
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Run time' }));

    expect((await screen.findByText('overhead-chart')).dataset.label).toBe(
      'Run time'
    );
    expect(
      screen.getByRole('heading', { name: 'Run time' })
    ).toBeInTheDocument();
    expect(series).toHaveBeenCalledWith('duration.', '6h', 'workflow:w');
  });

  it('says so, in the view it is in, when nothing was timed', () => {
    series.mockReturnValue(ok({}));
    renderWithProviders(<OverheadPanel scope="global" range="1h" live />);
    expect(
      screen.getByText('No engine timings in this range')
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Run time' }));
    expect(
      screen.getByText('No runs finished in this range')
    ).toBeInTheDocument();
  });
});
