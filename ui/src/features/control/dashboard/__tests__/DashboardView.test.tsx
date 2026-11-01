// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { DashboardView } from '../DashboardView';

const sessions = vi.hoisted(() => ({
  value: [] as { sessionId: string; name: string }[],
}));

vi.mock('@/features/sessions/hooks/useSessionSummaries', () => ({
  useSessionSummaries: () => ({ data: sessions.value }),
}));
vi.mock('@/features/metrics/ScopeBar', () => ({
  ScopeBar: ({ scopes }: { scopes: { value: string; label: string }[] }) => (
    <div data-testid="scopes">{scopes.map((s) => s.label).join('|')}</div>
  ),
}));
vi.mock('@/features/metrics/hooks', () => ({
  useDashboardParams: () => ({ scope: 'global', range: '1h', live: true }),
  useMetricStream: vi.fn(),
}));
vi.mock('../KioskView', () => ({
  KioskView: ({ onExit }: { onExit: () => void }) => (
    <button onClick={onExit}>leave-kiosk</button>
  ),
}));
vi.mock('../CommandStrip', () => ({ CommandStrip: () => null }));
vi.mock('../CapacityPanel', () => ({ CapacityPanel: () => null }));
vi.mock('../ChartPanels', () => ({
  LatencyPanel: () => null,
  ThroughputPanel: () => null,
}));
vi.mock('../RunPanels', () => ({
  HeatPanel: () => null,
  LeaderboardPanel: () => null,
  LiveRunsPanel: () => null,
}));
vi.mock('../HealthPanels', () => ({
  AiUsagePanel: () => null,
  BusPanel: () => null,
  VitalsPanel: () => null,
  WorkerSlotsPanel: () => null,
}));

describe('DashboardView', () => {
  it('names each session scope, falling back to its id without a name', () => {
    sessions.value = [
      { sessionId: 's1', name: 'Alpha' },
      { sessionId: 's2', name: '' },
    ];
    renderWithProviders(<DashboardView />);
    expect(screen.getByTestId('scopes').textContent).toBe(
      'Global|Session Alpha|Session s2'
    );
  });

  it('opens the kiosk and returns from it', () => {
    sessions.value = [];
    renderWithProviders(<DashboardView />);
    fireEvent.click(screen.getByRole('button', { name: 'Kiosk' }));
    fireEvent.click(screen.getByRole('button', { name: 'leave-kiosk' }));
    expect(screen.getByRole('button', { name: 'Kiosk' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'leave-kiosk' })).toBeNull();
  });
});
