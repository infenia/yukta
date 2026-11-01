// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { useMe } from '@/lib/identity';
import { getDrain, setDrain } from '../../api/drain';
import { getLimits, setLimits } from '../../api/scheduler';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { CapacityPanel } from '../CapacityPanel';

vi.mock('../../api/drain', () => ({
  getDrain: vi.fn(),
  setDrain: vi.fn(async () => ({})),
}));
vi.mock('../../api/scheduler', () => ({
  getLimits: vi.fn(),
  setLimits: vi.fn(async () => ({})),
  limitText: (n: number) => (n === 0 ? 'unlimited' : String(n)),
}));
vi.mock('@/lib/identity', () => ({
  useMe: vi.fn(),
  REQUIRED_ROLE: { changeLimits: 'ADMIN' },
}));
vi.mock('@/features/metrics/hooks', () => ({ useMetricSnapshot: vi.fn() }));
vi.mock('@/components/dashboard/Gauges', () => ({
  ArcGauge: () => <div>gauge</div>,
}));
vi.mock('@/components/dashboard/Knob', () => ({
  Knob: ({
    label,
    value,
    disabled,
    onChange,
    onCommit,
  }: {
    label: string;
    value: number;
    disabled?: boolean;
    onChange: (v: number) => void;
    onCommit: (v: number) => void;
  }) => (
    <div>
      <span data-testid="knob" data-disabled={String(!!disabled)}>
        {label}:{value}
      </span>
      <button onClick={() => onChange(7)}>turn</button>
      <button onClick={() => onCommit(7)}>commit-7</button>
      <button onClick={() => onCommit(value)}>commit-same</button>
    </div>
  ),
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

  it('says so when there is no scheduler', async () => {
    vi.mocked(getLimits).mockResolvedValue(null as never);
    renderWithProviders(<CapacityPanel live={false} />);
    expect(await screen.findByText('No scheduler')).toBeInTheDocument();
  });

  it('shows running against the limit and only the refused reasons with a rate', async () => {
    renderWithProviders(<CapacityPanel live />);
    expect(await screen.findByText('4 of 4')).toBeInTheDocument();
    expect(screen.getByText('1 of 10')).toBeInTheDocument();
    expect(screen.getByText('limit: 30.0/min')).toBeInTheDocument();
    expect(screen.queryByText(/queue:/)).toBeNull();
  });

  it('starts a drain only after confirming it', async () => {
    renderWithProviders(<CapacityPanel live={false} />);
    const drain = await screen.findByRole('switch');
    fireEvent.click(drain);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(setDrain).not.toHaveBeenCalled();
    fireEvent.click(drain);
    fireEvent.click(screen.getByRole('button', { name: 'Drain' }));
    await waitFor(() => expect(setDrain).toHaveBeenCalled());
    expect(vi.mocked(setDrain).mock.calls[0][0]).toBe(true);
  });

  it('ends a drain straight away and labels what is still running', async () => {
    vi.mocked(getDrain).mockResolvedValue({ draining: true, idle: false } as never);
    renderWithProviders(<CapacityPanel live={false} />);
    const drain = await screen.findByRole('switch');
    expect(screen.getByText('(running work finishes)')).toBeInTheDocument();
    fireEvent.click(drain);
    await waitFor(() => expect(setDrain).toHaveBeenCalled());
    expect(vi.mocked(setDrain).mock.calls[0][0]).toBe(false);
  });

  it('changes the limit when a different value is committed', async () => {
    renderWithProviders(<CapacityPanel live={false} />);
    await screen.findByRole('switch');
    fireEvent.click(screen.getByRole('button', { name: 'turn' }));
    fireEvent.click(screen.getByRole('button', { name: 'commit-same' }));
    expect(setLimits).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'commit-7' }));
    await waitFor(() => expect(setLimits).toHaveBeenCalledWith(7, 2));
  });

  it('locks the controls for a key that may not change limits', async () => {
    vi.mocked(useMe).mockReturnValue(me(false) as never);
    renderWithProviders(<CapacityPanel live={false} />);
    const knob = await screen.findByTestId('knob');
    expect(knob.getAttribute('data-disabled')).toBe('true');
    expect(screen.getByRole('switch')).toBeDisabled();
  });

  it('shows why a limit change was refused', async () => {
    vi.mocked(setLimits).mockRejectedValueOnce(new ApiError(403));
    renderWithProviders(<CapacityPanel live={false} />);
    await screen.findByRole('switch');
    fireEvent.click(screen.getByRole('button', { name: 'commit-7' }));
    expect(
      await screen.findByText('This needs an ADMIN key.')
    ).toBeInTheDocument();
  });

  it('shows a drain failure with its message and labels an idle drain', async () => {
    vi.mocked(getDrain).mockResolvedValue({ draining: true, idle: true } as never);
    vi.mocked(setDrain).mockRejectedValueOnce(new Error('drain refused'));
    renderWithProviders(<CapacityPanel live={false} />);
    expect(await screen.findByText('(idle)')).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('switch'));
    expect(await screen.findByText('drain refused')).toBeInTheDocument();
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

  it('closes the drain confirmation with Escape', async () => {
    renderWithProviders(<CapacityPanel live={false} />);
    fireEvent.click(await screen.findByRole('switch'));
    expect(screen.getByText('Drain the server?')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('Drain the server?')).toBeNull();
  });
});
