// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CartesianGrid, Line, LineChart, XAxis, YAxis } from 'recharts';
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart';
import type { MetricPoint } from '@/features/metrics/api';
import { formatMs } from './format';

const LINES = [
  { key: 'duration.p50', label: 'p50', color: 'var(--chart-1)' },
  { key: 'duration.p95', label: 'p95', color: 'var(--chart-2)' },
  { key: 'duration.p99', label: 'p99', color: 'var(--chart-4)' },
  { key: 'queue.wait.p95', label: 'queue wait p95', color: 'var(--chart-5)' },
];

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** Run duration percentiles and the queue wait. The default export, so a route can `lazy()` it. */
export default function LatencyChart({
  series,
}: {
  series: Record<string, MetricPoint[]>;
}) {
  const rows = new Map<number, Record<string, number>>();
  for (const { key } of LINES) {
    for (const p of series[key] ?? []) {
      rows.set(p.t, { ...rows.get(p.t), t: p.t, [key]: p.v });
    }
  }
  const data = [...rows.values()].sort((a, b) => a.t - b.t);
  const config: ChartConfig = Object.fromEntries(
    LINES.map((l) => [l.key.replace(/\./g, '_'), l])
  );
  return (
    <ChartContainer config={config} label="Run duration percentiles">
      <LineChart data={data} margin={{ left: 0, right: 8, top: 4 }}>
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
          width={56}
          tickFormatter={(v) => formatMs(Number(v))}
          tickLine={false}
          axisLine={false}
        />
        <ChartTooltip
          labelFormatter={(t) => time(Number(t))}
          formatter={(value, name) => [formatMs(Number(value)), String(name)]}
        />
        {LINES.map((l) => (
          <Line
            key={l.key}
            dataKey={l.key}
            name={l.label}
            type="monotone"
            stroke={l.color}
            dot={false}
            connectNulls
            isAnimationActive={false}
          />
        ))}
      </LineChart>
    </ChartContainer>
  );
}
