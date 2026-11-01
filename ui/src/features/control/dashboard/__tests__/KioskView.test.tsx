// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { KioskView, KIOSK_CYCLE_MS } from '../KioskView';

// The panels fetch their own data; here each is a named stand-in so the groups can be told apart.
vi.mock('../HealthPanels', () => ({
  AiUsagePanel: () => <div>ai</div>,
  BusPanel: () => <div>bus</div>,
  WorkerSlotsPanel: () => <div>slots</div>,
}));
vi.mock('../CapacityPanel', () => ({
  CapacityPanel: () => <div>capacity</div>,
}));
vi.mock('../ChartPanels', () => ({
  LatencyPanel: () => <div>latency</div>,
  ThroughputPanel: () => <div>throughput</div>,
}));
vi.mock('../CommandStrip', () => ({
  CommandStrip: () => <div>strip</div>,
}));
vi.mock('../RunPanels', () => ({
  HeatPanel: () => <div>heat</div>,
  LeaderboardPanel: () => <div>leaders</div>,
  LiveRunsPanel: () => <div>live-runs</div>,
}));
vi.mock('@/features/metrics/hooks', () => ({
  useDashboardParams: () => ({ scope: 'global', range: '1h', live: true }),
  useMetricStream: vi.fn(),
}));

describe('KioskView', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('leaves on Escape and on the exit button', () => {
    const onExit = vi.fn();
    render(<KioskView onExit={onExit} />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onExit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /exit/i }));
    expect(onExit).toHaveBeenCalledTimes(2);
  });

  it('cycles the groups on a timer', () => {
    render(<KioskView onExit={vi.fn()} />);
    expect(screen.getByText('throughput')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('latency')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('bus')).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('throughput')).toBeInTheDocument();
  });

  it('jumps to a group from its button', () => {
    render(<KioskView onExit={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Group 3' }));
    expect(screen.getByRole('button', { name: 'Group 3' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText('heat')).toBeInTheDocument();
  });

  it('holds the current group while the pointer is over it', () => {
    render(<KioskView onExit={vi.fn()} />);
    const kiosk = screen.getByTestId('kiosk');
    fireEvent.mouseEnter(kiosk);
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS * 2));
    expect(screen.getByText('throughput')).toBeInTheDocument();
    fireEvent.mouseLeave(kiosk);
    act(() => vi.advanceTimersByTime(KIOSK_CYCLE_MS));
    expect(screen.getByText('latency')).toBeInTheDocument();
  });

  it('ignores keys other than Escape', () => {
    const onExit = vi.fn();
    render(<KioskView onExit={onExit} />);
    fireEvent.keyDown(window, { key: 'a' });
    expect(onExit).not.toHaveBeenCalled();
  });
});
