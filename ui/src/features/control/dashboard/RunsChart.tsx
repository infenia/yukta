// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart';
import type { MetricPoint } from '@/features/metrics/api';

const COLOR: Record<string, string> = {
  SUCCESS: 'var(--success)',
  FAILURE: 'var(--destructive)',
  ERROR: 'var(--destructive)',
  TIMED_OUT: 'var(--warning)',
  CANCELLED: 'var(--chart-3)',
  STOPPED: 'var(--chart-3)',
};

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** Runs that ended per minute, stacked by status. The default export, so a route can `lazy()` it. */
export default function RunsChart({
  series,
}: {
  series: Record<string, MetricPoint[]>;
}) {
  const statuses = Object.keys(series)
    .filter((key) => key.startsWith('runs.') && key !== 'runs.finished')
    .map((key) => key.slice('runs.'.length));
  const rows = new Map<number, Record<string, number>>();
  for (const status of statuses) {
    for (const p of series[`runs.${status}`]) {
      rows.set(p.t, { ...rows.get(p.t), t: p.t, [status]: p.v });
    }
  }
  const data = [...rows.values()].sort((a, b) => a.t - b.t);
  const config: ChartConfig = Object.fromEntries(
    statuses.map((s) => [s, { label: s, color: COLOR[s] ?? 'var(--chart-1)' }])
  );
  return (
    <ChartContainer config={config} label="Runs per minute by status">
      <AreaChart data={data} margin={{ left: 0, right: 8, top: 4 }}>
        <CartesianGrid vertical={false} />
        <XAxis
          dataKey="t"
          type="number"
          scale="time"
          domain={['dataMin', 'dataMax']}
          tickFormatter={time}
          tickLine={false}
          axisLine={false}
        />
        <YAxis
          width={32}
          allowDecimals={false}
          tickLine={false}
          axisLine={false}
        />
        <ChartTooltip labelFormatter={(t) => time(Number(t))} />
        {statuses.map((status) => (
          <Area
            key={status}
            dataKey={status}
            stackId="runs"
            type="monotone"
            stroke={`var(--color-${status})`}
            fill={`var(--color-${status})`}
            fillOpacity={0.35}
            isAnimationActive={false}
          />
        ))}
      </AreaChart>
    </ChartContainer>
  );
}
