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
  {
    key: 'overhead.handoff.p50',
    label: 'hand-off p50',
    color: 'var(--chart-1)',
  },
  {
    key: 'overhead.handoff.p95',
    label: 'hand-off p95',
    color: 'var(--chart-2)',
  },
  {
    key: 'overhead.start.p95',
    label: 'run start p95',
    color: 'var(--chart-4)',
  },
];

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * What the engine added: the hand-off to a plugin and the start of a run, never the plugins' own
 * time. The default export, so a route can `lazy()` it.
 */
export default function OverheadChart({
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
    <ChartContainer config={config} label="Engine overhead">
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
