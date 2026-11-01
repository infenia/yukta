// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMetricSeries, useAnnotations } from '@/features/metrics/hooks';
import { ThroughputPanel, LatencyPanel } from '../ChartPanels';

const nav = vi.hoisted(() => ({ to: vi.fn() }));

vi.mock('react-router', async (orig) => ({
  ...(await orig<typeof import('react-router')>()),
  useNavigate: () => nav.to,
}));
vi.mock('@/features/metrics/hooks', () => ({
  useMetricSeries: vi.fn(),
  useAnnotations: vi.fn(),
}));
vi.mock('../useNow', () => ({ useNow: () => 1_000_000 }));
vi.mock('../RunsChart', () => ({
  default: () => <div>runs-chart</div>,
}));
vi.mock('../LatencyChart', () => ({
  default: () => <div>latency-chart</div>,
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
const ok = (data: unknown) =>
  ({ isPending: false, error: null, data, refetch: vi.fn() }) as never;

describe('ThroughputPanel', () => {
  beforeEach(() => {
    nav.to.mockReset();
    series.mockReturnValue(ok({ 'runs.finished{status=ok}': [] }));
  });

  it('labels marks by command, actor and whether they were applied', () => {
    annotations.mockReturnValue(
      ok([
        { time: 1, command: 'pause', actor: 'ops', applied: true },
        { time: 2, command: 'stop', applied: false },
      ]) as never
    );
    renderWithProviders(
      <ThroughputPanel scope="global" range="1h" live />
    );
    expect(screen.getByText('pause by ops|attention')).toBeInTheDocument();
    expect(screen.getByText('stop (refused)|danger')).toBeInTheDocument();
  });

  it('opens the workflow of a mark that has one', () => {
    annotations.mockReturnValue(
      ok([
        { time: 1, command: 'pause', applied: true, sessionId: 's1', workflowId: 'w1' },
        { time: 2, command: 'stop', applied: true },
      ]) as never
    );
    renderWithProviders(
      <ThroughputPanel scope="global" range="1h" live />
    );
    fireEvent.click(screen.getByText(/pause/));
    expect(nav.to).toHaveBeenCalledWith('/sessions/s1/workflow/w1');
    fireEvent.click(screen.getByText(/stop/));
    expect(nav.to).toHaveBeenCalledTimes(1);
  });
});

describe('LatencyPanel', () => {
  it('retries the timings when they fail to load', () => {
    const refetch = vi.fn();
    series.mockReturnValue({
      isPending: false,
      error: new Error('slow'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(<LatencyPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });

});

describe('ThroughputPanel failures', () => {
  it('retries the runs when they fail to load', () => {
    const refetch = vi.fn();
    series.mockReturnValue({ isPending: false, error: new Error('x'), data: undefined, refetch } as never);
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
      ok([{ time: 1, command: 'pause', applied: true, sessionId: 's1', workflowId: 'w1' }]) as never
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
