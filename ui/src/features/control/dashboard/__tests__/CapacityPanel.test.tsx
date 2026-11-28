// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMe } from '@/lib/identity';
import { getDrain } from '../../api/drain';
import { getLimits } from '../../api/scheduler';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { CapacityPanel } from '../CapacityPanel';

vi.mock('../../api/drain', () => ({ getDrain: vi.fn() }));
vi.mock('../../api/scheduler', () => ({
  getLimits: vi.fn(),
  limitText: (n: number) => (n === 0 ? 'unlimited' : String(n)),
}));
vi.mock('@/lib/identity', () => ({ useMe: vi.fn() }));
vi.mock('@/features/metrics/hooks', () => ({ useMetricSnapshot: vi.fn() }));
vi.mock('@/components/dashboard/Gauges', () => ({
  ArcGauge: () => <div>gauge</div>,
}));

const limits = {
  maxConcurrent: 4,
  maxPerWorkflow: 2,
  running: 4,
  queued: 1,
  queueCapacity: 10,
};

const me = (changeLimits: boolean) => ({ permissions: { changeLimits } });

describe('CapacityPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getLimits).mockResolvedValue(limits as never);
    vi.mocked(getDrain).mockResolvedValue({
      draining: false,
      idle: true,
    } as never);
    vi.mocked(useMe).mockReturnValue(me(true) as never);
    vi.mocked(useMetricSnapshot).mockReturnValue({
      data: {
        'yukta.executions.rejected{reason=limit}': 0.5,
        'yukta.executions.rejected{reason=queue}': 0,
      },
    } as never);
  });

  it('shows running against the limit, the queue and only the refused reasons with a rate', async () => {
    renderWithProviders(<CapacityPanel live />);
    expect(await screen.findByText('4 of 4')).toBeInTheDocument();
    expect(screen.getByText('1 of 10')).toBeInTheDocument();
    expect(screen.getByText('at most 2')).toBeInTheDocument();
    expect(screen.getByText('limit: 30.0/min')).toBeInTheDocument();
    expect(screen.queryByText(/queue:/)).toBeNull();
  });

  it('is read-only: no knob, switch or Edit limits, only a link to Configuration', async () => {
    renderWithProviders(<CapacityPanel live={false} />);
    await screen.findByText('at most 2');
    expect(screen.queryByRole('switch')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit limits' })).toBeNull();
    expect(
      screen.getByRole('link', { name: 'Change limits in Configuration' })
    ).toHaveAttribute('href', '/config?tab=capacity');
  });

  it('hides the link from a key that may not change limits', async () => {
    vi.mocked(useMe).mockReturnValue(me(false) as never);
    renderWithProviders(<CapacityPanel live={false} />);
    await screen.findByText('at most 2');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('says so while draining while work finishes', async () => {
    vi.mocked(getDrain).mockResolvedValue({
      draining: true,
      idle: false,
    } as never);
    renderWithProviders(<CapacityPanel live={false} />);
    expect(
      await screen.findByText(/Draining: new starts are refused, running work/)
    ).toBeInTheDocument();
  });

  it('says so when it is draining and idle', async () => {
    vi.mocked(getDrain).mockResolvedValue({
      draining: true,
      idle: true,
    } as never);
    renderWithProviders(<CapacityPanel live={false} />);
    expect(await screen.findByText(/\(idle\)/)).toBeInTheDocument();
  });

  it('says so when there is no scheduler', async () => {
    vi.mocked(getLimits).mockResolvedValue(null as never);
    renderWithProviders(<CapacityPanel live={false} />);
    expect(await screen.findByText('No scheduler')).toBeInTheDocument();
  });

  it('names a refused reason that carries no tag by its key', async () => {
    vi.mocked(useMetricSnapshot).mockReturnValue({
      data: { 'yukta.executions.rejected': 0.1 },
    } as never);
    renderWithProviders(<CapacityPanel live={false} />);
    expect(
      await screen.findByText('yukta.executions.rejected: 6.0/min')
    ).toBeInTheDocument();
  });

  it('retries the limits when they fail to load', async () => {
    vi.mocked(getLimits).mockRejectedValue(new Error('scheduler down'));
    renderWithProviders(<CapacityPanel live={false} />);
    fireEvent.click(await screen.findByRole('button', { name: /retry/i }));
    await waitFor(() => expect(getLimits).toHaveBeenCalledTimes(2));
  });
});
