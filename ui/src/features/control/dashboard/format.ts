// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Tone } from '@/components/dashboard/tones';
import type { MetricPoint } from '@/features/metrics/api';

/** A duration in milliseconds as a person reads it: 850 ms, 1.8 s, 2 m 5 s. */
export function formatMs(ms: number): string {
  if (!Number.isFinite(ms)) return '–';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  return `${minutes} m ${Math.round((ms % 60_000) / 1000)} s`;
}

/** The sum of every value whose key starts with `prefix` and ends with `suffix`. */
export function sumOf(
  values: Record<string, number> | undefined,
  prefix: string,
  suffix = ''
): number {
  return Object.entries(values ?? {})
    .filter(([key]) => key.startsWith(prefix) && key.endsWith(suffix))
    .reduce((total, [, v]) => total + v, 0);
}

/** A value of `{tag=value}` in a series key, such as the `reason` of a rejected-start rate. */
export function tagOf(key: string, tag: string): string | undefined {
  return new RegExp(`[{,]${tag}=([^,}|]*)`).exec(key)?.[1];
}

/** Points sorted by time, from several series added up minute by minute. */
export function addUp(series: MetricPoint[][]): MetricPoint[] {
  const byTime = new Map<number, number>();
  for (const points of series) {
    for (const p of points) byTime.set(p.t, (byTime.get(p.t) ?? 0) + p.v);
  }
  return [...byTime.entries()]
    .sort(([a], [b]) => a - b)
    .map(([t, v]) => ({ t, v }));
}

/** Success rate as a percentage, or null without runs. */
export function successRate(runs: number, failed: number): number | null {
  return runs > 0 ? Math.round(((runs - failed) / runs) * 100) : null;
}

/**
 * The change from the first to the last point of a series as a whole percentage, or undefined
 * when it cannot be told (fewer than two points, or a start of zero).
 */
export function percentChange(points: MetricPoint[]): number | undefined {
  if (points.length < 2) return undefined;
  const first = points[0].v;
  const last = points[points.length - 1].v;
  if (first === 0) return undefined;
  return Math.round(((last - first) / Math.abs(first)) * 100);
}

/** A change as a person reads it: +12%, -3%, 0%; undefined stays undefined. */
export function formatChange(change: number | undefined): string | undefined {
  if (change === undefined) return undefined;
  return `${change > 0 ? '+' : ''}${change}%`;
}

/** The tone of a change in a duration: longer is a warning, shorter a success. */
export function slowerIsWorse(change: number | undefined): Tone {
  if (change === undefined || change === 0) return 'neutral';
  return change > 0 ? 'warning' : 'success';
}

/**
 * What a per-second rate added up to over its points: each point's rate times the time since the
 * point before it. The first point only marks where the window starts.
 */
export function integrate(points: MetricPoint[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += (points[i].v * (points[i].t - points[i - 1].t)) / 1000;
  }
  return total;
}

/**
 * "100 of 400 MB": bytes used out of a maximum, both in one unit (GB from a billion bytes up) so
 * the caption stays short. A maximum that is not known (zero or less) shows the used amount alone.
 */
export function usedOfBytes(used: number, max: number): string {
  const big = Math.max(used, max) >= 1e9;
  const [divisor, unit, digits] = big ? [1e9, 'GB', 1] : [1e6, 'MB', 0];
  const fmt = (n: number) => (n / divisor).toFixed(digits);
  return max > 0
    ? `${fmt(used)} of ${fmt(max)} ${unit}`
    : `${fmt(used)} ${unit}`;
}
