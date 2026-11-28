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
  format = String,
}: {
  points: MetricPoint[];
  label: string;
  color?: string;
  /** how a value reads on the axis and in the tooltip */
  format?: (value: number) => string;
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
        <YAxis
          width={48}
          tickLine={false}
          axisLine={false}
          tickFormatter={format}
        />
        <ChartTooltip
          labelFormatter={(t) => time(Number(t))}
          formatter={(value) => [format(Number(value)), label]}
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
