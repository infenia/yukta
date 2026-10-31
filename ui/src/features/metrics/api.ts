// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

// ponytail: the endpoint shapes follow the plan in #1585 and are not served yet; adjust here
// once that issue lands. Nothing else in the UI knows the URLs.

export const RANGES = ['15m', '1h', '6h', '24h'] as const;
export type Range = (typeof RANGES)[number];

/** `global`, `session:<id>` or `workflow:<sessionId>/<workflowId>` */
export type Scope = string;

export interface MetricPoint {
  /** epoch milliseconds */
  t: number;
  v: number;
}

export interface MetricSample {
  metric: string;
  t: number;
  v: number;
}

const query = (params: Record<string, string>) =>
  new URLSearchParams(params).toString();

export const getSeries = (metric: string, scope: Scope, range: Range) =>
  fetchApi<MetricPoint[]>(
    `/api/metrics/series?${query({ metric, scope, range })}`
  );

export const getSnapshot = (scope: Scope) =>
  fetchApi<Record<string, number>>(`/api/metrics/snapshot?${query({ scope })}`);

export const streamUrl = (scope: Scope) =>
  `/api/metrics/stream?${query({ scope })}`;
