// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { OutcomesPanel } from '../OutcomesPanel';

vi.mock('@/features/metrics/hooks', () => ({ useMetricSnapshot: vi.fn() }));
vi.mock('@/components/dashboard/Gauges', () => ({
  Donut: ({ segments }: { segments: { label: string; tone: string }[] }) => (
    <div>donut:{segments.map((s) => `${s.label}/${s.tone}`).join(',')}</div>
  ),
}));

const snapshot = vi.mocked(useMetricSnapshot);
const ok = (data: unknown) =>
  ({ isPending: false, error: null, data, refetch: vi.fn() }) as never;

describe('OutcomesPanel', () => {
  it('shows how the hour ended as shares, largest first, in a tone each', () => {
    snapshot.mockReturnValue(
      ok({
        'runs.finished': 10,
        'runs.failed': 2,
        'runs.FAILURE': 1,
        'runs.SUCCESS': 6,
        'runs.TIMED_OUT': 1,
        'runs.CANCELLED': 2,
        'duration.p95': 40,
      })
    );
    renderWithProviders(<OutcomesPanel scope="session:s1" />);

    expect(
      screen.getByText(
        'donut:SUCCESS/success,CANCELLED/neutral,FAILURE/danger,TIMED_OUT/warning'
      )
    ).toBeInTheDocument();
    expect(screen.getByText('6 (60%)')).toBeInTheDocument();
    expect(screen.getByText('2 (20%)')).toBeInTheDocument();
    expect(snapshot).toHaveBeenCalledWith('session:s1');
  });

  it('says so when no run finished, and retries a failed read', () => {
    snapshot.mockReturnValue(ok({ 'runs.finished': 0 }));
    const { unmount } = renderWithProviders(<OutcomesPanel scope="global" />);
    expect(
      screen.getByText('No runs finished in the last hour')
    ).toBeInTheDocument();
    unmount();

    const refetch = vi.fn();
    snapshot.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(<OutcomesPanel scope="global" />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});
