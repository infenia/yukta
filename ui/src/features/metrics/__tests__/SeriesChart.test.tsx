// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// The chart elements only describe a drawing; the data and colour handed to them are under test
vi.mock('recharts', () => {
  const Stub = (name: string) => (props: Record<string, unknown>) => (
    <div data-testid={name} data-props={JSON.stringify(props.data ?? props)}>
      {props.children as React.ReactNode}
    </div>
  );
  return {
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
      <div>{children}</div>
    ),
    // the formatters are what the chart shows, so run them with a sample value
    Tooltip: (props: Record<string, unknown>) => (
      <div data-testid="tooltip">
        {String((props.labelFormatter as (t: number) => string)(0))}|
        {String((props.formatter as (v: number, n: string) => unknown)(3, 'v'))}
      </div>
    ),
    AreaChart: Stub('area-chart'),
    Area: Stub('area'),
    CartesianGrid: Stub('grid'),
    XAxis: (props: Record<string, unknown>) => (
      <div data-testid="x">
        {String((props.tickFormatter as (t: number) => string)(0))}
      </div>
    ),
    YAxis: Stub('y'),
  };
});

import SeriesChart from '../SeriesChart';

describe('SeriesChart', () => {
  const points = [
    { t: 1000, v: 1 },
    { t: 2000, v: 3 },
  ];

  it('names the chart and passes the points on', () => {
    render(<SeriesChart points={points} label="Runs per minute" />);
    expect(
      screen.getByRole('img', { name: 'Runs per minute' })
    ).toBeInTheDocument();
    expect(
      JSON.parse(screen.getByTestId('area-chart').dataset.props ?? '')
    ).toEqual(points);
  });

  it('uses the first chart colour unless given another', () => {
    const { rerender } = render(<SeriesChart points={points} label="Runs" />);
    expect(screen.getByRole('img').style.getPropertyValue('--color-v')).toBe(
      'var(--chart-1)'
    );
    rerender(
      <SeriesChart points={points} label="Runs" color="var(--chart-3)" />
    );
    expect(screen.getByRole('img').style.getPropertyValue('--color-v')).toBe(
      'var(--chart-3)'
    );
  });

  it('renders with no points', () => {
    render(<SeriesChart points={[]} label="Empty" />);
    expect(screen.getByRole('img', { name: 'Empty' })).toBeInTheDocument();
  });
});

describe('SeriesChart formatting', () => {
  it('formats the time axis and the tooltip', () => {
    render(
      <SeriesChart
        points={[{ t: 1000, v: 1 }]}
        label="Runs per minute"
      />
    );
    expect(screen.getByTestId('x').textContent).toMatch(/\d{2}:\d{2}/);
    expect(screen.getByTestId('tooltip').textContent).toContain(
      'Runs per minute'
    );
  });
});
