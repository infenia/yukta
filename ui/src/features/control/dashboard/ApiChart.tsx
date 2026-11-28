// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart';
import type { MetricPoint } from '@/features/metrics/api';
import { OPERATIONS, opOf } from './format';

const COLOR: Record<string, string> = {
  read: 'var(--chart-1)',
  run: 'var(--chart-2)',
  change: 'var(--chart-3)',
  other: 'var(--chart-4)',
};

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * REST calls per minute, stacked by what they do. The series are per-second rates, so each point
 * is multiplied by 60. The default export, so a panel can `lazy()` it.
 */
export default function ApiChart({
  series,
}: {
  series: Record<string, MetricPoint[]>;
}) {
  const ops = OPERATIONS.filter((op) =>
    Object.keys(series).some((key) => opOf(key) === op && key.endsWith('|rate'))
  );
  const rows = new Map<number, Record<string, number>>();
  for (const [key, points] of Object.entries(series)) {
    const op = opOf(key);
    if (op && key.endsWith('|rate')) {
      for (const p of points) {
        rows.set(p.t, { ...rows.get(p.t), t: p.t, [op]: p.v * 60 });
      }
    }
  }
  const data = [...rows.values()].sort((a, b) => a.t - b.t);
  const config: ChartConfig = Object.fromEntries(
    ops.map((op) => [op, { label: op, color: COLOR[op] }])
  );
  return (
    <ChartContainer config={config} label="API calls per minute by kind">
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
        <YAxis width={32} tickLine={false} axisLine={false} />
        <ChartTooltip labelFormatter={(t) => time(Number(t))} />
        {ops.map((op) => (
          <Area
            key={op}
            dataKey={op}
            stackId="api"
            type="monotone"
            stroke={`var(--color-${op})`}
            fill={`var(--color-${op})`}
            fillOpacity={0.35}
            isAnimationActive={false}
          />
        ))}
      </AreaChart>
    </ChartContainer>
  );
}
