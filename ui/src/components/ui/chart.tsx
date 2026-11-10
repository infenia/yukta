// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { CSSProperties, ReactNode } from 'react';
import { ResponsiveContainer, Tooltip } from 'recharts';
import { cn } from '@/lib/utils';

/** Series key -> label and colour token (`var(--chart-1)` .. `var(--chart-5)`, or a status hue). */
export type ChartConfig = Record<string, { label: string; color: string }>;

/**
 * shadcn-style chart frame: sizes the Recharts child to its box and exposes each series colour as
 * `--color-<key>`, so a child can write `fill="var(--color-runs)"` and follow the light/dark tokens.
 * Import it only from lazy routes: Recharts is a chunk of its own (see `vite.config.ts`).
 */
export function ChartContainer({
  config,
  className,
  label,
  children,
}: {
  config: ChartConfig;
  className?: string;
  /** Accessible name of the chart */
  label: string;
  children: ReactNode;
}) {
  const style = Object.fromEntries(
    Object.entries(config).map(([key, { color }]) => [`--color-${key}`, color])
  ) as CSSProperties;
  return (
    <div
      role="img"
      aria-label={label}
      className={cn(
        'text-label-sm text-muted-foreground [&_.recharts-cartesian-grid_line]:stroke-border h-48 w-full [&_.recharts-cartesian-axis-tick_text]:fill-current',
        className
      )}
      style={style}
    >
      <ResponsiveContainer width="100%" height="100%">
        {children as React.ReactElement}
      </ResponsiveContainer>
    </div>
  );
}

/** Tooltip on the surface tokens, with tabular numbers. */
export function ChartTooltip(props: React.ComponentProps<typeof Tooltip>) {
  return (
    <Tooltip
      contentStyle={{
        background: 'var(--popover)',
        color: 'var(--popover-foreground)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        fontVariantNumeric: 'tabular-nums',
      }}
      isAnimationActive={false}
      {...props}
    />
  );
}
