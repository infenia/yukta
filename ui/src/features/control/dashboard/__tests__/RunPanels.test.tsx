// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMe } from '@/lib/identity';
import { runAction } from '@/features/workflow/api/executionControl';
import {
  useHeatmap,
  useLiveRuns,
  useModules,
  useNodes,
} from '@/features/metrics/hooks';
import {
  DayHeat,
  LeaderboardPanel,
  LiveRunsPanel,
  NodesPanel,
} from '../RunPanels';

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
  useNodes: vi.fn(),
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
  ({
    isPending: false,
    error: null,
    data: [],
    refetch: vi.fn(),
    ...over,
  }) as never;

const run = (over: Record<string, unknown> = {}) => ({
  executionId: 'e1',
  sessionId: 's1',
  workflowId: 'wf',
  elapsedMs: 1000,
  p95Ms: null,
  nodesDone: 1,
  nodesSeen: 2,
  paused: false,
  currentNodes: [] as string[],
  ...over,
});

describe('DayHeat', () => {
  it('shows the day in five-minute cells with the number of runs', () => {
    vi.mocked(useHeatmap).mockReturnValue(
      q({
        data: [
          { time: 9 * 60_000, runs: 3, failed: 1 },
          { time: 8 * 60_000, runs: 2, failed: 0 },
        ],
      })
    );
    renderWithProviders(<DayHeat scope="global" live />);
    expect(
      screen.getByText(/Runs per five minutes, 24 hours:288/)
    ).toBeInTheDocument();
    expect(screen.getByText('5 runs')).toBeInTheDocument();
  });

  it('shows an empty day', () => {
    vi.mocked(useHeatmap).mockReturnValue(q());
    renderWithProviders(<DayHeat scope="global" live />);
    expect(screen.getByText('0 runs')).toBeInTheDocument();
  });

  it('shows nothing while loading and a line when it fails', () => {
    vi.mocked(useHeatmap).mockReturnValue(q({ isPending: true }));
    const { container, unmount } = renderWithProviders(
      <DayHeat scope="global" live />
    );
    expect(container).toBeEmptyDOMElement();
    unmount();
    vi.mocked(useHeatmap).mockReturnValue(q({ error: new Error('x') }));
    renderWithProviders(<DayHeat scope="global" live />);
    expect(
      screen.getByText('Could not load the last 24 hours.')
    ).toBeInTheDocument();
  });
});

describe('LeaderboardPanel', () => {
  it('ranks plugins with their failures and leaves out a mean that would mean nothing', () => {
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
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('–')).toBeInTheDocument();
    expect(screen.queryByText('Mean')).toBeNull();
  });

  it('says so when no node finished, and retries', () => {
    const refetch = vi.fn();
    vi.mocked(useModules).mockReturnValue(q({ refetch }));
    const { unmount } = renderWithProviders(
      <LeaderboardPanel scope="global" range="1h" live />
    );
    expect(
      screen.getByText('No nodes finished in this range')
    ).toBeInTheDocument();
    unmount();
    vi.mocked(useModules).mockReturnValue(
      q({ error: new Error('x'), refetch })
    );
    renderWithProviders(<LeaderboardPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

const node = (over: Record<string, unknown> = {}) => ({
  sessionId: 's1',
  workflowId: 'wf',
  nodeId: 'build',
  module: 'PROCESS_EXECUTOR',
  runs: 4,
  failures: 0,
  p50Ms: 1000,
  p95Ms: 5000,
  lastExecutionId: 'e9',
  ...over,
});

describe('NodesPanel', () => {
  it('names each slow node with its workflow and session and links to its last run', () => {
    vi.mocked(useNodes).mockReturnValue(
      q({
        data: [
          node(),
          node({ nodeId: 'lint', failures: 2, p50Ms: 0, p95Ms: 0 }),
        ],
      })
    );
    renderWithProviders(<NodesPanel scope="global" range="1h" live />);
    const link = screen.getByRole('link', { name: 'build' });
    expect(link).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/wf?exec=e9&node=build'
    );
    expect(screen.getAllByText('PROCESS_EXECUTOR').length).toBe(2);
    expect(screen.getAllByText('wf').length).toBe(2);
    expect(screen.getByText('5.0 s')).toBeInTheDocument();
    // a node that was never timed has no duration, and a failure is counted
    expect(screen.getAllByText('–').length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('shows the first rows and all of them on request', () => {
    vi.mocked(useNodes).mockReturnValue(
      q({
        data: Array.from({ length: 10 }, (_, i) => node({ nodeId: `n${i}` })),
      })
    );
    renderWithProviders(<NodesPanel scope="global" range="1h" live />);
    expect(screen.queryByRole('link', { name: 'n9' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show all 10' }));
    expect(screen.getByRole('link', { name: 'n9' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show fewer' }));
    expect(screen.queryByRole('link', { name: 'n9' })).toBeNull();
  });

  it('says so when no node finished, and retries', () => {
    const refetch = vi.fn();
    vi.mocked(useNodes).mockReturnValue(q({ refetch }));
    const { unmount } = renderWithProviders(
      <NodesPanel scope="global" range="1h" live />
    );
    expect(
      screen.getByText('No nodes finished in this range')
    ).toBeInTheDocument();
    unmount();
    vi.mocked(useNodes).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<NodesPanel scope="global" range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('LiveRunsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useMe).mockReturnValue({
      permissions: { controlRuns: true },
    } as never);
  });

  it('shows the runs in scope and flags one that runs past its usual time', () => {
    vi.mocked(useLiveRuns).mockReturnValue(
      q({
        data: [
          run({ executionId: 'e1', workflowId: 'a', sessionId: 's1' }),
          run({
            executionId: 'e2',
            workflowId: 'b',
            sessionId: 's2',
            elapsedMs: 9000,
            p95Ms: 5000,
          }),
        ],
      })
    );
    renderWithProviders(<LiveRunsPanel scope="session:s1" live />);
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.queryByText('b')).toBeNull();

    vi.mocked(useLiveRuns).mockReturnValue(
      q({
        data: [
          run({
            executionId: 'e2',
            workflowId: 'b',
            elapsedMs: 9000,
            p95Ms: 5000,
          }),
        ],
      })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(screen.getByText(/of usual/)).toBeInTheDocument();
    expect(screen.getByText(/\(slow\)/)).toBeInTheDocument();
  });

  it('names the running nodes and offers Resume, not Pause, for a paused run', () => {
    vi.mocked(useLiveRuns).mockReturnValue(
      q({
        data: [
          run({
            executionId: 'e1',
            workflowId: 'a',
            paused: true,
            currentNodes: ['n1', 'n2', 'n3', 'n4', 'n5'],
          }),
          run({ executionId: 'e2', workflowId: 'b' }),
        ],
      })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(screen.getByText('n1, n2, n3 +2')).toBeInTheDocument();
    expect(screen.getByText('between nodes')).toBeInTheDocument();
    expect(screen.getByText(/paused/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'resume a run e1' })
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'pause a run e1' })).toBeNull();
    expect(
      screen.getByRole('button', { name: 'pause b run e2' })
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'resume b run e2' })
    ).toBeNull();
  });

  it('pauses a run straight away and stops one only after confirming', async () => {
    vi.mocked(useLiveRuns).mockReturnValue(
      q({ data: [run({ executionId: 'e1', workflowId: 'a' })] })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: 'pause a run e1' }));
    await waitFor(() => expect(runAction).toHaveBeenCalled());
    expect(vi.mocked(runAction).mock.calls[0][0]).toBe('e1');
    expect(vi.mocked(runAction).mock.calls[0][1]).toBe('pause');

    fireEvent.click(screen.getByRole('button', { name: 'stop a run e1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'stop a run e1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Stop run' }));
    await waitFor(() =>
      expect(vi.mocked(runAction).mock.calls.at(-1)?.[1]).toBe('stop')
    );
  });

  it('locks run controls for a key that may not control runs', () => {
    vi.mocked(useMe).mockReturnValue({
      permissions: { controlRuns: false },
    } as never);
    vi.mocked(useLiveRuns).mockReturnValue(
      q({ data: [run({ executionId: 'e1', workflowId: 'a' })] })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    expect(
      screen.getByRole('button', { name: 'pause a run e1' })
    ).toBeDisabled();
  });

  it('shows the empty state, a failed action and a retry', async () => {
    vi.mocked(useLiveRuns).mockReturnValue(q({ data: [] }));
    const { unmount } = renderWithProviders(
      <LiveRunsPanel scope="global" live />
    );
    expect(screen.getByText('No run is going')).toBeInTheDocument();
    unmount();

    vi.mocked(runAction).mockRejectedValueOnce(new Error('refused'));
    vi.mocked(useLiveRuns).mockReturnValue(
      q({ data: [run({ executionId: 'e1', workflowId: 'a' })] })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: 'pause a run e1' }));
    expect(await screen.findByText('refused')).toBeInTheDocument();

    const refetch = vi.fn();
    vi.mocked(useLiveRuns).mockReturnValue(
      q({ error: new Error('x'), refetch })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });

  it('closes the stop confirmation with Escape', () => {
    vi.mocked(useMe).mockReturnValue({
      permissions: { controlRuns: true },
    } as never);
    vi.mocked(useLiveRuns).mockReturnValue(
      q({ data: [run({ executionId: 'e1', workflowId: 'a' })] })
    );
    renderWithProviders(<LiveRunsPanel scope="global" live />);
    fireEvent.click(screen.getByRole('button', { name: 'stop a run e1' }));
    expect(screen.getByText('Stop a?')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('Stop a?')).toBeNull();
  });
});
