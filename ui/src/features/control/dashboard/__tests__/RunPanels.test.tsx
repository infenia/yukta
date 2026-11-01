// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMe } from '@/lib/identity';
import { runAction } from '@/features/workflow/api/executionControl';
import { useHeatmap, useLiveRuns, useModules } from '@/features/metrics/hooks';
import { HeatPanel, LeaderboardPanel, LiveRunsPanel } from '../RunPanels';

vi.mock('../useNow', () => ({ useNow: () => 10 * 60_000 }));
vi.mock('@/features/workflow/api/executionControl', () => ({
  runAction: vi.fn(async () => ({})),
}));
vi.mock('@/lib/identity', () => ({
  useMe: vi.fn(),
  REQUIRED_ROLE: { controlRuns: 'OPERATOR' },
}));
vi.mock('@/features/metrics/hooks', () => ({
  useHeatmap: vi.fn(),
  useModules: vi.fn(),
  useLiveRuns: vi.fn(),
}));
vi.mock('@/components/dashboard/Strips', () => ({
  HeatStrip: ({ label, cells }: { label: string; cells: unknown[] }) => (
    <div>
      {label}:{cells.length}
    </div>
  ),
}));
vi.mock('@/components/dashboard/Gauges', () => ({
  MeterBar: ({ label }: { label: string }) => <div>{label}</div>,
}));

const q = (over: Record<string, unknown> = {}) =>
  ({ isPending: false, error: null, data: [], refetch: vi.fn(), ...over }) as never;

const run = (over: Record<string, unknown> = {}) => ({
  executionId: 'e1',
  sessionId: 's1',
  workflowId: 'wf',
  elapsedMs: 1000,
  p95Ms: null,
  nodesDone: 1,
  nodesSeen: 2,
  ...over,
});

describe('HeatPanel', () => {
  it('shows the day of runs, the empty state and a retry', () => {
    vi.mocked(useHeatmap).mockReturnValue(q({ data: [{ time: 9 * 60_000, runs: 3, failed: 1 }] }));
    const { unmount } = renderWithProviders(<HeatPanel scope="global" live />);
    expect(screen.getByText(/Runs per minute, 24 hours:1440/)).toBeInTheDocument();
    unmount();

    vi.mocked(useHeatmap).mockReturnValue(q());
    const empty = renderWithProviders(<HeatPanel scope="global" live />);
    expect(screen.getByText('No runs in the last 24 hours')).toBeInTheDocument();
    empty.unmount();

    const refetch = vi.fn();
    vi.mocked(useHeatmap).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<HeatPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('LeaderboardPanel', () => {
  it('ranks plugins with their failure share, and handles a plugin with no runs', () => {
    vi.mocked(useModules).mockReturnValue(
      q({
        data: [
          { module: 'llm-call', executions: 4, failures: 1, avgMs: 1500 },
          { module: 'idle', executions: 0, failures: 0, avgMs: 0 },
        ],
      })
    );
    renderWithProviders(<LeaderboardPanel scope="global" range="1h" live />);
    expect(screen.getByText('llm-call')).toBeInTheDocument();
    expect(screen.getByText('25%')).toBeInTheDocument();
    expect(screen.getByText('0%')).toBeInTheDocument();
  });

  it('says so when no node finished, and retries', () => {
    const refetch = vi.fn();
    vi.mocked(useModules).mockReturnValue(q({ refetch }));
    const { unmount } = renderWithProviders(<LeaderboardPanel scope="global" range="1h" live />);
    expect(screen.getByText('No nodes finished in this range')).toBeInTheDocument();
    unmount();
    vi.mocked(useModules).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<LeaderboardPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('LiveRunsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useMe).mockReturnValue({ permissions: { controlRuns: true } } as never);
  });

  it('shows the runs in scope and flags one that runs past its usual time', () => {
    vi.mocked(useLiveRuns).mockReturnValue(
      q({
        data: [
          run({ executionId: 'e1', workflowId: 'a', sessionId: 's1' }),
          run({ executionId: 'e2', workflowId: 'b', sessionId: 's2', elapsedMs: 9000, p95Ms: 5000 }),
        ],
      })
    );
    renderWithProviders(<LiveRunsPanel scope="session:s1" live />);
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.queryByText('b')).toBeNull();

    vi.mocked(useLiveRuns).mockReturnValue(
      q({
        data: [run({ executionId: 'e2', workflowId: 'b', elapsedMs: 9000, p95Ms: 5000 })],
      })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(screen.getByText(/of usual/)).toBeInTheDocument();
    expect(screen.getByText(/\(slow\)/)).toBeInTheDocument();
  });

  it('pauses a run straight away and stops one only after confirming', async () => {
    vi.mocked(useLiveRuns).mockReturnValue(q({ data: [run({ executionId: 'e1', workflowId: 'a' })] }));
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: 'pause a run e1' }));
    await waitFor(() => expect(runAction).toHaveBeenCalled());
    expect(vi.mocked(runAction).mock.calls[0][0]).toBe('e1');
    expect(vi.mocked(runAction).mock.calls[0][1]).toBe('pause');

    fireEvent.click(screen.getByRole('button', { name: 'stop a run e1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'stop a run e1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Stop run' }));
    await waitFor(() => expect(vi.mocked(runAction).mock.calls.at(-1)?.[1]).toBe('stop'));
  });

  it('locks run controls for a key that may not control runs', () => {
    vi.mocked(useMe).mockReturnValue({ permissions: { controlRuns: false } } as never);
    vi.mocked(useLiveRuns).mockReturnValue(q({ data: [run({ executionId: 'e1', workflowId: 'a' })] }));
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(screen.getByRole('button', { name: 'pause a run e1' })).toBeDisabled();
  });

  it('shows the empty state, a failed action and a retry', async () => {
    vi.mocked(useLiveRuns).mockReturnValue(q({ data: [] }));
    const { unmount } = renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(screen.getByText('No run is going')).toBeInTheDocument();
    unmount();

    vi.mocked(runAction).mockRejectedValueOnce(new Error('refused'));
    vi.mocked(useLiveRuns).mockReturnValue(q({ data: [run({ executionId: 'e1', workflowId: 'a' })] }));
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: 'pause a run e1' }));
    expect(await screen.findByText('refused')).toBeInTheDocument();

    const refetch = vi.fn();
    vi.mocked(useLiveRuns).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});
