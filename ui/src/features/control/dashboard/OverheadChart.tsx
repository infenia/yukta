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

type Line = { key: string; label: string; color: string };

const LINES: Line[] = [
  {
    key: 'overhead.start.p50',
    label: 'run start p50',
    color: 'var(--chart-1)',
  },
  {
    key: 'overhead.start.p95',
    label: 'run start p95',
    color: 'var(--chart-2)',
  },
  {
    key: 'overhead.handoff.p95',
    label: 'hand-off p95',
    color: 'var(--chart-4)',
  },
];

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * What the engine added: the start of a run (registration to its first plugin call, queue wait
 * excluded) and, for nodes that run per message, the hand-off to a plugin; never the plugins' own
 * time. The default export, so a route can `lazy()` it.
 */
export default function OverheadChart({
  series,
  lines = LINES,
  label = 'Engine overhead',
}: {
  series: Record<string, MetricPoint[]>;
  /** the lines to draw; the engine's by default */
  lines?: Line[];
  label?: string;
}) {
  const rows = new Map<number, Record<string, number>>();
  for (const { key } of lines) {
    for (const p of series[key] ?? []) {
      rows.set(p.t, { ...rows.get(p.t), t: p.t, [key]: p.v });
    }
  }
  const data = [...rows.values()].sort((a, b) => a.t - b.t);
  const config: ChartConfig = Object.fromEntries(
    lines.map((l) => [l.key.replace(/\./g, '_'), l])
  );
  return (
    <ChartContainer config={config} label={label}>
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
        {lines.map((l) => (
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
