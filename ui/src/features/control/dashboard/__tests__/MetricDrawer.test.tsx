// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import { MetricDrawer, type DrillSpec } from '../MetricDrawer';

// Recharts is exercised by the end-to-end specs; here the chart is a stand-in.
vi.mock('@/features/metrics/SeriesChart', () => ({
  default: ({
    points,
    label,
    format,
  }: {
    points: unknown[];
    label: string;
    format: (v: number) => string;
  }) => (
    <p>
      chart:{label}:{points.length}:{format(7)}
    </p>
  ),
}));

const spec = (over: Partial<DrillSpec> = {}): DrillSpec => ({
  title: 'Queued',
  window: 'last hour',
  info: 'Runs waiting for a slot.',
  points: [
    { t: 1, v: 0 },
    { t: 2, v: 4 },
    { t: 3, v: 2 },
  ],
  ...over,
});

describe('MetricDrawer', () => {
  it('shows what the series means, its numbers and the larger chart', async () => {
    renderWithProviders(<MetricDrawer spec={spec()} onClose={vi.fn()} />);
    expect(screen.getByRole('dialog', { name: 'Queued' })).toBeInTheDocument();
    expect(
      screen.getByText('Runs waiting for a slot. Showing the last hour.')
    ).toBeInTheDocument();
    expect(screen.getByText('Latest').nextSibling).toHaveTextContent('2');
    expect(screen.getByText('Average').nextSibling).toHaveTextContent('2');
    expect(screen.getByText('Lowest').nextSibling).toHaveTextContent('0');
    expect(screen.getByText('Highest').nextSibling).toHaveTextContent('4');
    expect(await screen.findByText('chart:Queued:3:7')).toBeInTheDocument();
  });

  it('formats the numbers and the chart with the given format', async () => {
    renderWithProviders(
      <MetricDrawer spec={spec({ format: (v) => `${v}%` })} onClose={vi.fn()} />
    );
    expect(screen.getByText('Highest').nextSibling).toHaveTextContent('4%');
    expect(await screen.findByText('chart:Queued:3:7%')).toBeInTheDocument();
  });

  it('says so, with no numbers or chart, when the window has too little data', () => {
    renderWithProviders(
      <MetricDrawer
        spec={spec({ points: [{ t: 1, v: 1 }] })}
        onClose={vi.fn()}
      />
    );
    expect(
      screen.getByText('Not enough data in this window yet')
    ).toBeInTheDocument();
    expect(screen.queryByText(/chart:/)).toBeNull();
  });

  it('shows no numbers at all without points', () => {
    renderWithProviders(
      <MetricDrawer spec={spec({ points: [] })} onClose={vi.fn()} />
    );
    expect(screen.queryByText('Latest')).toBeNull();
  });

  it('closes from the Close button and from Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(<MetricDrawer spec={spec()} onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
