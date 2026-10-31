// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import {
  RANGES,
  getSeries,
  getSnapshot,
  streamUrl,
  type MetricPoint,
  type MetricSample,
  type Range,
  type Scope,
} from './api';

const RANGE_MS: Record<Range, number> = {
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '6h': 6 * 60 * 60_000,
  '24h': 24 * 60 * 60_000,
};

export const useMetricSeries = (metric: string, scope: Scope, range: Range) =>
  useQuery({
    queryKey: ['metrics', 'series', metric, scope, range],
    queryFn: () => getSeries(metric, scope, range),
  });

export const useMetricSnapshot = (scope: Scope) =>
  useQuery({
    queryKey: ['metrics', 'snapshot', scope],
    queryFn: () => getSnapshot(scope),
  });

/**
 * Keeps the series and snapshot of `scope` current from the server's stream: each sample is
 * appended to every cached range of its metric (dropping points older than the range) and
 * written into the snapshot. Idle when `live` is false.
 */
export function useMetricStream(scope: Scope, live: boolean) {
  const client = useQueryClient();
  const onSample = useCallback(
    ({ metric, t, v }: MetricSample) => {
      for (const range of RANGES) {
        client.setQueryData<MetricPoint[]>(
          ['metrics', 'series', metric, scope, range],
          (old) =>
            old && [...old.filter((p) => p.t > t - RANGE_MS[range]), { t, v }]
        );
      }
      client.setQueryData<Record<string, number>>(
        ['metrics', 'snapshot', scope],
        (old) => ({ ...old, [metric]: v })
      );
    },
    [client, scope]
  );
  return useEventSourceJson<MetricSample>(
    live ? streamUrl(scope) : null,
    onSample
  );
}

/** Scope, range and live/paused, kept in the URL so a view can be shared and survives a reload. */
export function useDashboardParams() {
  const [params, setParams] = useSearchParams();
  const rangeParam = params.get('range');
  const range: Range = RANGES.includes(rangeParam as Range)
    ? (rangeParam as Range)
    : '1h';
  const scope = params.get('scope') ?? 'global';
  const live = params.get('live') !== '0';
  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === null) {
          next.delete(key);
        } else {
          next.set(key, value);
        }
        return next;
      },
      { replace: true }
    );
  return {
    scope,
    range,
    live,
    setScope: (v: Scope) => set('scope', v === 'global' ? null : v),
    setRange: (v: Range) => set('range', v === '1h' ? null : v),
    setLive: (v: boolean) => set('live', v ? null : '0'),
  };
}
