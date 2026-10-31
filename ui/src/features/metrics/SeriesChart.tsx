// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts';
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart';
import type { MetricPoint } from './api';

const time = (t: number) =>
  new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** One series as an area over time. The default export, so a route can `lazy()` it. */
export default function SeriesChart({
  points,
  label,
  color = 'var(--chart-1)',
}: {
  points: MetricPoint[];
  label: string;
  color?: string;
}) {
  const config: ChartConfig = { v: { label, color } };
  return (
    <ChartContainer config={config} label={label}>
      <AreaChart data={points} margin={{ left: 0, right: 8, top: 4 }}>
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
        <YAxis width={40} tickLine={false} axisLine={false} />
        <ChartTooltip
          labelFormatter={(t) => time(Number(t))}
          formatter={(value) => [String(value), label]}
        />
        <Area
          dataKey="v"
          type="monotone"
          stroke="var(--color-v)"
          fill="var(--color-v)"
          fillOpacity={0.2}
          isAnimationActive={false}
        />
      </AreaChart>
    </ChartContainer>
  );
}
