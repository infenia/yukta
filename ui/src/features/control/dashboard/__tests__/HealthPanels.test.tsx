// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { getHeartbeats } from '../../api/controlBus';
import { useWorkers } from '../../api/workers';
import { AiUsagePanel, BusPanel, VitalsPanel, WorkerSlotsPanel } from '../HealthPanels';

const NOW = 1_000_000;

vi.mock('../useNow', () => ({ useNow: () => NOW }));
vi.mock('../../api/controlBus', () => ({ getHeartbeats: vi.fn() }));
vi.mock('../../api/workers', () => ({ useWorkers: vi.fn() }));
vi.mock('@/features/metrics/hooks', () => ({ useMetricSnapshot: vi.fn() }));
vi.mock('@/components/dashboard/Gauges', () => ({
  ArcGauge: ({ label }: { label: string }) => <div>{label}</div>,
  MeterBar: ({ label }: { label: string }) => <div>{label}</div>,
}));
vi.mock('@/components/dashboard/Strips', () => ({
  StatusMatrix: ({ items }: { items: { label: string; state: string }[] }) => (
    <div>
      {items.map((i) => (
        <span key={i.label}>
          {i.label}={i.state}
        </span>
      ))}
    </div>
  ),
}));

const q = (over: Record<string, unknown> = {}) =>
  ({ isPending: false, error: null, data: [], refetch: vi.fn(), ...over }) as never;

describe('BusPanel', () => {
  it('says so with no heartbeat and grades the age of each', async () => {
    vi.mocked(getHeartbeats).mockResolvedValue([] as never);
    const { unmount } = renderWithProviders(<BusPanel live={false} />);
    expect(await screen.findByText('No node has sent a heartbeat')).toBeInTheDocument();
    unmount();

    const beats = [
      { workflowId: 'w', nodeId: 'fresh', executionId: 'e1', timestamp: new Date(NOW - 10_000).toISOString() },
      { workflowId: 'w', nodeId: 'late', executionId: 'e2', timestamp: new Date(NOW - 45_000).toISOString() },
      { workflowId: 'w', nodeId: 'gone', executionId: 'e3', timestamp: new Date(NOW - 90_000).toISOString() },
    ];
    vi.mocked(getHeartbeats).mockResolvedValue(beats as never);
    renderWithProviders(<BusPanel live />);
    expect(await screen.findByText('w / fresh=ok')).toBeInTheDocument();
    expect(screen.getByText('w / late=stale')).toBeInTheDocument();
    expect(screen.getByText('w / gone=down')).toBeInTheDocument();
  });
});

describe('WorkerSlotsPanel', () => {
  it('shows each worker, its drain and whether it is full', () => {
    vi.mocked(useWorkers).mockReturnValue(q({ data: [] }));
    const { unmount } = renderWithProviders(<WorkerSlotsPanel />);
    expect(screen.getByText('No worker is connected')).toBeInTheDocument();
    unmount();

    vi.mocked(useWorkers).mockReturnValue(
      q({
        data: [
          { workerId: 'a', draining: true, usedSlots: 2, capacity: 2 },
          { workerId: 'b', draining: false, usedSlots: 0, capacity: 4 },
        ],
      })
    );
    renderWithProviders(<WorkerSlotsPanel />);
    expect(screen.getByText('a (draining): 2 of 2 slots')).toBeInTheDocument();
    expect(screen.getByText('b: 0 of 4 slots')).toBeInTheDocument();
  });

  it('retries when workers fail to load', () => {
    const refetch = vi.fn();
    vi.mocked(useWorkers).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<WorkerSlotsPanel />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('VitalsPanel', () => {
  it('shows heap, CPU, threads, the longest GC pause and dropped events', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(
      q({
        data: {
          'jvm.memory.used{area=heap,id=eden}': 100_000_000,
          'jvm.memory.max{area=heap,id=eden}': 400_000_000,
          'process.cpu.usage': 0.25,
          'jvm.threads.live': 120,
          'jvm.gc.pause{cause=x}|max': 42,
          'yukta.tracker.dropped{x=1}|rate': 0.5,
        },
      })
    );
    renderWithProviders(<VitalsPanel />);
    expect(screen.getByText('Heap 100 of 400 MB')).toBeInTheDocument();
    expect(screen.getByText('CPU 25%')).toBeInTheDocument();
    expect(screen.getByText('Threads 120')).toBeInTheDocument();
    expect(screen.getByText('42 ms')).toBeInTheDocument();
    expect(screen.getByText('30.0')).toBeInTheDocument();
  });

  it('says so without samples and retries a failed read', () => {
    const refetch = vi.fn();
    vi.mocked(useMetricSnapshot).mockReturnValue(q({ data: {}, refetch }));
    const { unmount } = renderWithProviders(<VitalsPanel />);
    expect(screen.getByText('No samples yet')).toBeInTheDocument();
    unmount();

    vi.mocked(useMetricSnapshot).mockReturnValue(q({ error: new Error('x'), refetch }));
    renderWithProviders(<VitalsPanel />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('AiUsagePanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the rates for the global scope, with precision by size', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(
      q({
        data: {
          'llm.tokens{x=1}|rate': 2,
          'llm.cost{x=1}|rate': 0.001,
          'yukta.mcp.calls{x=1}|rate': 1,
        },
      })
    );
    renderWithProviders(<AiUsagePanel scope="global" />);
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText('3.60')).toBeInTheDocument();
    expect(screen.getByText('60')).toBeInTheDocument();
  });

  it('explains that usage is server-wide outside the global scope', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(q({ data: {} }));
    renderWithProviders(<AiUsagePanel scope="session:s1" />);
    expect(
      screen.getByText('AI usage is server-wide; choose the global scope')
    ).toBeInTheDocument();
  });

  it('says so when there was no model or tool call', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(q({ data: {} }));
    renderWithProviders(<AiUsagePanel scope="global" />);
    expect(screen.getByText('No model or tool calls yet')).toBeInTheDocument();
  });

it('retries the AI usage when it fails to load', () => {
  const refetch = vi.fn();
  vi.mocked(useMetricSnapshot).mockReturnValue(q({ error: new Error('x'), refetch }));
  renderWithProviders(<AiUsagePanel scope="global" />);
  fireEvent.click(screen.getByRole('button', { name: /retry/i }));
  expect(refetch).toHaveBeenCalled();
});
});
