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

interface HeatCell {
  time: number;
  runs: number;
  failed: number;
}

/** Heat-map cells summed into `bucketMs` buckets, oldest first. */
function bucketHeat(cells: HeatCell[], bucketMs: number) {
  const buckets = new Map<number, { runs: number; failed: number }>();
  for (const c of cells) {
    const key = Math.floor(c.time / bucketMs);
    const sum = buckets.get(key) ?? { runs: 0, failed: 0 };
    buckets.set(key, {
      runs: sum.runs + c.runs,
      failed: sum.failed + c.failed,
    });
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => a - b)
    .map(([key, sum]) => ({ t: key * bucketMs, ...sum }));
}

/** The success rate (percent) per half hour; a half hour with no runs has no point. */
export function successPoints(cells: HeatCell[], bucketMs = 30 * 60_000) {
  return bucketHeat(cells, bucketMs)
    .filter((b) => b.runs > 0)
    .map((b) => ({
      t: b.t,
      v: Math.round(((b.runs - b.failed) / b.runs) * 100),
    }));
}

/** Failed runs per five minutes over the last hour, a quiet bucket counted as zero. */
export function failedPoints(
  cells: HeatCell[],
  now: number,
  bucketMs = 5 * 60_000
) {
  const buckets = new Map(
    bucketHeat(cells, bucketMs).map((b) => [b.t, b.failed])
  );
  const last = Math.floor(now / bucketMs);
  return Array.from({ length: 12 }, (_, i) => {
    const t = (last - 11 + i) * bucketMs;
    return { t, v: buckets.get(t) ?? 0 };
  });
}

/** The kinds of REST call `yukta.api.requests` is tagged with, in the order they are drawn. */
export const OPERATIONS = ['read', 'run', 'change', 'other'] as const;

/** The kind of call a `yukta.api.requests{op=...}` series key is for, or undefined. */
export const opOf = (key: string): string | undefined =>
  OPERATIONS.find((op) => key.includes(`{op=${op}}`));

/** Lines of the run-time chart: how long a run took, not what the engine added. */
export const RUN_LINES = [
  { key: 'duration.p50', label: 'run p50', color: 'var(--chart-1)' },
  { key: 'duration.p95', label: 'run p95', color: 'var(--chart-2)' },
  { key: 'duration.p99', label: 'run p99', color: 'var(--chart-4)' },
];

/** How long ago, as a person says it: "just now", "12 min ago", "3 h ago", "2 d ago". */
export function agoText(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} h ago` : `${Math.floor(hours / 24)} d ago`;
}

/** The start of the minute of the newest run in the cells, or undefined when none ran. */
export function lastRunAt(cells: HeatCell[]): number | undefined {
  const times = cells.filter((c) => c.runs > 0).map((c) => c.time);
  return times.length > 0 ? Math.max(...times) : undefined;
}

/** Failed runs in the last hour and in the hour before it. */
export function failedHours(cells: HeatCell[], now: number) {
  const hour = 60 * 60_000;
  let last = 0;
  let previous = 0;
  for (const c of cells) {
    const age = now - c.time;
    if (age >= 0 && age < hour) last += c.failed;
    else if (age >= hour && age < 2 * hour) previous += c.failed;
  }
  return { last, previous };
}

/** The ids of the failed runs the cells name, newest first, each with the minute it ended. */
export function failedRunIds(cells: (HeatCell & { failedRuns: string[] })[]) {
  return cells
    .flatMap((c) => c.failedRuns.map((id) => ({ time: c.time, id })))
    .sort((a, b) => b.time - a.time);
}

/** The longest garbage-collection pause in a snapshot, in milliseconds (0 when none). */
export function longestGcPause(values: Record<string, number> | undefined) {
  return Math.max(
    0,
    ...Object.entries(values ?? {})
      .filter(([k]) => k.startsWith('jvm.gc.pause') && k.endsWith('|max'))
      .map(([, v]) => v)
  );
}

/** A share of the heap or host memory above which the System card says so. */
const HEAP_WARN = 0.85;
const HOST_WARN = 0.9;
/** A garbage-collection pause longer than this, in milliseconds, is worth a word. */
const GC_WARN_MS = 1000;

/** What on the System card needs a look: a full heap, full host memory, a long pause, drops. */
export function healthConcerns(values: Record<string, number> | undefined) {
  const concerns: string[] = [];
  const heapMax = sumOf(values, 'jvm.memory.max{area=heap');
  const heap =
    heapMax > 0 ? sumOf(values, 'jvm.memory.used{area=heap') / heapMax : 0;
  if (heap > HEAP_WARN) concerns.push(`Heap ${Math.round(heap * 100)}% full`);
  const total = values?.['yukta.system.memory.total'] ?? 0;
  const host =
    total > 0 ? 1 - (values?.['yukta.system.memory.free'] ?? 0) / total : 0;
  if (host > HOST_WARN)
    concerns.push(`Host memory ${Math.round(host * 100)}% used`);
  const pause = longestGcPause(values);
  if (pause > GC_WARN_MS) concerns.push(`GC pause ${formatMs(pause)}`);
  if (sumOf(values, 'yukta.tracker.dropped', '|rate') > 0)
    concerns.push('Events dropped');
  return concerns;
}
