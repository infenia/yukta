// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import type { MetricPoint } from '@/features/metrics/api';
import OverheadChart from '../OverheadChart';
import { formatMs } from '../format';

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
  LineChart: (props: ChartProps) => {
    chart.props = props;
    return <div>line-chart</div>;
  },
}));

const point = (t: number, v: number) => ({ t, v }) as MetricPoint;

/** The child of the chart that carries the given prop. */
function child(name: string): ReactElement<Record<string, unknown>> {
  const found = chart.props?.children.find((c) => name in c.props);
  if (!found) throw new Error(`no child with ${name}`);
  return found as ReactElement<Record<string, unknown>>;
}

describe('OverheadChart', () => {
  it('merges the lines by time, sorts them and skips missing series', () => {
    render(
      <OverheadChart
        series={{
          'overhead.handoff.p50': [point(2000, 5), point(1000, 3)],
          'overhead.handoff.p95': [point(1000, 9)],
        }}
      />
    );

    expect(screen.getByText('line-chart')).toBeInTheDocument();
    expect(chart.props?.data).toEqual([
      { t: 1000, 'overhead.handoff.p50': 3, 'overhead.handoff.p95': 9 },
      { t: 2000, 'overhead.handoff.p50': 5 },
    ]);
  });

  it('formats the axes, the tooltip and names the lines', () => {
    render(<OverheadChart series={{}} />);

    const tickTime = child('tickFormatter').props.tickFormatter as (
      t: number
    ) => string;
    expect(tickTime(Date.UTC(2026, 0, 1, 12, 30))).toEqual(expect.any(String));

    const tickMs = child('width').props.tickFormatter as (v: number) => string;
    expect(tickMs(1500)).toBe(formatMs(1500));

    const tip = child('labelFormatter').props as {
      labelFormatter: (t: number) => string;
      formatter: (v: number, n: string) => [string, string];
    };
    expect(tip.labelFormatter(Date.UTC(2026, 0, 1))).toEqual(expect.any(String));
    expect(tip.formatter(1500, 'hand-off p50')).toEqual([
      formatMs(1500),
      'hand-off p50',
    ]);

    const lines = (chart.props?.children ?? []).flat() as ReactElement<
      Record<string, unknown>
    >[];
    expect(lines.filter((l) => 'name' in l.props).map((l) => l.props.name))
      .toEqual([
      'hand-off p50',
      'hand-off p95',
      'run start p95',
    ]);
  });
});
