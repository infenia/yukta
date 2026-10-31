// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
