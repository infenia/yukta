// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

export const RANGES = ['15m', '1h', '6h', '24h'] as const;
export type Range = (typeof RANGES)[number];

export const RANGE_MS: Record<Range, number> = {
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '24h': 24 * 60 * 60_000,
};

/** `global`, `session:<id>` or `workflow:<id>`; kept in the URL. */
export type Scope = string;

export const GLOBAL: Scope = 'global';

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

const query = (params: Record<string, string>, scope: Scope) =>
  new URLSearchParams(
    scope === GLOBAL ? params : { ...params, scope }
  ).toString();

/**
 * The history of every series whose key starts with `name` (a meter name selects all its tag
 * variants), keyed by series key. A scope other than global has only the run series (`runs.*`,
 * `duration.*`, `queue.*`).
 */
export const getSeries = async (
  name: string,
  range: Range,
  scope: Scope = GLOBAL
): Promise<Record<string, MetricPoint[]>> => {
  const { series } = await fetchApi<SeriesResponse>(
    `/api/metrics/series?${query({ names: name, range }, scope)}`
  );
  return Object.fromEntries(
    Object.entries(series).map(([key, points]) => [
      key,
      points.map((p) => ({ t: p.time, v: p.value })),
    ])
  );
};

export const getSnapshot = async (
  scope: Scope = GLOBAL
): Promise<Record<string, number>> =>
  (await fetchApi<MetricSample>(`/api/metrics/snapshot?${query({}, scope)}`))
    .values;

export const STREAM_URL = '/api/metrics/stream';

/** One minute of the run heat strip. */
export interface HeatCell {
  time: number;
  runs: number;
  failed: number;
  failedRuns: string[];
}

export interface ModuleRow {
  module: string;
  executions: number;
  failures: number;
  avgMs: number;
}

export interface LiveRun {
  executionId: string;
  sessionId: string;
  workflowId: string;
  startedAt: number;
  elapsedMs: number;
  nodesDone: number;
  nodesSeen: number;
  /** the workflow's usual p95 duration, null when unknown */
  p95Ms: number | null;
}

/** A control command, for marking the throughput chart. */
export interface ControlAnnotation {
  time: number;
  executionId: string;
  sessionId: string | null;
  workflowId: string | null;
  command: string;
  actor: string | null;
  applied: boolean;
  reason: string | null;
}

export const getHeatmap = (range: Range, scope: Scope = GLOBAL) =>
  fetchApi<HeatCell[]>(`/api/metrics/heatmap?${query({ range }, scope)}`);

export const getModules = (range: Range, scope: Scope = GLOBAL) =>
  fetchApi<ModuleRow[]>(`/api/metrics/modules?${query({ range }, scope)}`);

export const getAnnotations = (range: Range, scope: Scope = GLOBAL) =>
  fetchApi<ControlAnnotation[]>(
    `/api/metrics/annotations?${query({ range }, scope)}`
  );

export const getLiveRuns = () => fetchApi<LiveRun[]>('/api/metrics/runs/live');
