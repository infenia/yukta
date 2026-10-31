// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

export const RANGES = ['15m', '1h', '6h', '24h'] as const;
export type Range = (typeof RANGES)[number];

/** `global`, `session:<id>` or `workflow:<sessionId>/<workflowId>`; kept in the URL. */
export type Scope = string;

export interface MetricPoint {
  /** epoch milliseconds */
  t: number;
  v: number;
}

/**
 * Every sampled value at one instant, as the server streams it. Keys look like
 * `yukta.executions.rejected{reason=limit}|rate`; counters and timers carry a `|rate`, `|mean`,
 * `|max` or `|p50|p95|p99` suffix (timers in milliseconds, rates per second).
 */
export interface MetricSample {
  time: number;
  values: Record<string, number>;
}

interface SeriesResponse {
  range: Range;
  series: Record<string, { time: number; value: number }[]>;
}

/**
 * The history of every series whose key starts with `name` (a meter name selects all its tag
 * variants), keyed by series key.
 */
export const getSeries = async (
  name: string,
  range: Range
): Promise<Record<string, MetricPoint[]>> => {
  const params = new URLSearchParams({ names: name, range });
  const { series } = await fetchApi<SeriesResponse>(
    `/api/metrics/series?${params}`
  );
  return Object.fromEntries(
    Object.entries(series).map(([key, points]) => [
      key,
      points.map((p) => ({ t: p.time, v: p.value })),
    ])
  );
};

export const getSnapshot = async (): Promise<Record<string, number>> =>
  (await fetchApi<MetricSample>('/api/metrics/snapshot')).values;

export const STREAM_URL = '/api/metrics/stream';
