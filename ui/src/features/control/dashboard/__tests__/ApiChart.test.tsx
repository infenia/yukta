// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import type { MetricPoint } from '@/features/metrics/api';
import ApiChart from '../ApiChart';

type ChartProps = { children: ReactElement[]; data: unknown[] };

const chart = vi.hoisted(() => ({ props: null as null | ChartProps }));

// the container sizes itself before it draws children: render them straight away
vi.mock('@/components/ui/chart', async (orig) => ({
  ...(await orig<typeof import('@/components/ui/chart')>()),
  ChartContainer: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
}));

// recharts draws nothing in jsdom (no size), so capture what the chart is given
vi.mock('recharts', async (orig) => ({
  ...(await orig<typeof import('recharts')>()),
  AreaChart: (props: ChartProps) => {
    chart.props = props;
    return <div>area-chart</div>;
  },
}));

const point = (t: number, v: number) => ({ t, v }) as MetricPoint;

describe('ApiChart', () => {
  it('turns the per-second rates into calls per minute, merged by time and stacked by kind', () => {
    render(
      <ApiChart
        series={{
          'yukta.api.requests{op=run}|rate': [point(1000, 0.5)],
          'yukta.api.requests{op=read}|rate': [point(2000, 2), point(1000, 1)],
          'yukta.api.requests{op=read}|mean': [point(1000, 12)],
        }}
      />
    );

    expect(screen.getByText('area-chart')).toBeInTheDocument();
    expect(chart.props?.data).toEqual([
      { t: 1000, read: 60, run: 30 },
      { t: 2000, read: 120 },
    ]);
    // one area per kind that has a rate, in the fixed order, all in one stack
    const areas = chart.props?.children
      .flat()
      .filter((c) => 'stackId' in c.props);
    expect(areas?.map((a) => (a.props as { dataKey: string }).dataKey)).toEqual(
      ['read', 'run']
    );
  });

  it('draws nothing for a series that is not a rate of a known kind', () => {
    render(
      <ApiChart
        series={{
          'yukta.api.requests{op=weird}|rate': [point(1, 1)],
          'yukta.api.requests{op=read}|max': [point(1, 1)],
        }}
      />
    );
    expect(chart.props?.data).toEqual([]);
  });

  it('formats the time axis and the tooltip label as a clock time', () => {
    render(<ApiChart series={{}} />);
    const axis = chart.props?.children
      .flat()
      .find((c) => 'tickFormatter' in c.props && 'dataKey' in c.props);
    const format = (axis?.props as { tickFormatter: (t: number) => string })
      .tickFormatter;
    expect(format(Date.UTC(2026, 0, 1, 13, 5))).toMatch(/\d/);
    const tooltip = chart.props?.children
      .flat()
      .find((c) => 'labelFormatter' in c.props);
    const label = (tooltip?.props as { labelFormatter: (t: number) => string })
      .labelFormatter;
    expect(label(Date.UTC(2026, 0, 1, 13, 5))).toBe(
      format(Date.UTC(2026, 0, 1, 13, 5))
    );
  });
});
