// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { useWorkers } from '../../api/workers';
import { SystemPanel, VitalsPanel, WorkerSlotsPanel } from '../HealthPanels';

vi.mock('../../api/workers', () => ({ useWorkers: vi.fn() }));
let edition = 'PLUS';
vi.mock('@/features/edition/hooks/useEdition', () => ({
  useEdition: () => ({ data: { edition } }),
}));
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
  ({
    isPending: false,
    error: null,
    data: [],
    refetch: vi.fn(),
    ...over,
  }) as never;

describe('WorkerSlotsPanel', () => {
  it('shows each worker, its drain and whether it is full', () => {
    vi.mocked(useWorkers).mockReturnValue(q({ data: [] }));
    const { unmount } = renderWithProviders(<WorkerSlotsPanel />);
    expect(screen.getByText('No workers connected')).toBeInTheDocument();
    unmount();
    edition = 'COMMUNITY';
    const community = renderWithProviders(<WorkerSlotsPanel />);
    expect(
      screen.getByText(/remote workers need the Plus edition/)
    ).toBeInTheDocument();
    community.unmount();
    edition = 'PLUS';

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
    vi.mocked(useWorkers).mockReturnValue(
      q({ error: new Error('x'), refetch })
    );
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
    expect(screen.getByText('JVM heap 25%, 100 of 400 MB')).toBeInTheDocument();
    expect(screen.getByText('JVM heap · 100 of 400 MB')).toBeInTheDocument();
    expect(screen.getByText('CPU 25%, process')).toBeInTheDocument();
    expect(screen.getByText('Threads 120, live')).toBeInTheDocument();
    expect(screen.getByText('42 ms')).toBeInTheDocument();
    expect(screen.getByText('30.0')).toBeInTheDocument();
  });

  it('shows the host memory beside the JVM heap, and the heap the JVM has reserved', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(
      q({
        data: {
          'jvm.memory.used{area=heap,id=eden}': 100_000_000,
          'jvm.memory.max{area=heap,id=eden}': 400_000_000,
          'jvm.memory.committed{area=heap,id=eden}': 250_000_000,
          'yukta.system.memory.total': 16_000_000_000,
          'yukta.system.memory.free': 4_000_000_000,
        },
      })
    );
    renderWithProviders(<VitalsPanel />);
    expect(
      screen.getByText('Host memory 75%, 12.0 of 16.0 GB')
    ).toBeInTheDocument();
    expect(screen.getByText('Heap reserved')).toBeInTheDocument();
    expect(screen.getByText('250 MB')).toBeInTheDocument();
  });

  it('leaves out the host memory and the reserved heap when the server does not report them', () => {
    vi.mocked(useMetricSnapshot).mockReturnValue(
      q({ data: { 'jvm.threads.live': 10 } })
    );
    renderWithProviders(<VitalsPanel />);
    expect(screen.queryByText(/Host memory/)).toBeNull();
    expect(screen.queryByText('Heap reserved')).toBeNull();
  });

  it('says so without samples and retries a failed read', () => {
    const refetch = vi.fn();
    vi.mocked(useMetricSnapshot).mockReturnValue(q({ data: {}, refetch }));
    const { unmount } = renderWithProviders(<VitalsPanel />);
    expect(screen.getByText('No samples yet')).toBeInTheDocument();
    unmount();

    vi.mocked(useMetricSnapshot).mockReturnValue(
      q({ error: new Error('x'), refetch })
    );
    renderWithProviders(<VitalsPanel />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('SystemPanel health chip', () => {
  const render = (data: Record<string, number>) => {
    vi.mocked(useWorkers).mockReturnValue(q({ data: [] }));
    vi.mocked(useMetricSnapshot).mockReturnValue(q({ data }));
    renderWithProviders(<SystemPanel live={false} />);
  };

  it('says what needs a look in the card header', () => {
    render({
      'jvm.memory.used{area=heap,id=eden}': 95,
      'jvm.memory.max{area=heap,id=eden}': 100,
      'yukta.tracker.dropped{type=log}|rate': 0.5,
    });
    expect(
      screen.getByText('Heap 95% full · Events dropped')
    ).toBeInTheDocument();
  });

  it('says nothing while all is well', () => {
    render({
      'jvm.memory.used{area=heap,id=eden}': 10,
      'jvm.memory.max{area=heap,id=eden}': 100,
    });
    expect(screen.queryByText(/Heap \d+% full|Events dropped/)).toBeNull();
  });
});
