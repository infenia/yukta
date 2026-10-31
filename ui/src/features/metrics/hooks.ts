// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import {
  RANGES,
  STREAM_URL,
  getSeries,
  getSnapshot,
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

type SeriesData = Record<string, MetricPoint[]>;

/** History of `name` and its tag variants over `range`, keyed by series key. */
export const useMetricSeries = (name: string, range: Range) =>
  useQuery({
    queryKey: ['metrics', 'series', name, range],
    queryFn: () => getSeries(name, range),
  });

export const useMetricSnapshot = () =>
  useQuery({ queryKey: ['metrics', 'snapshot'], queryFn: getSnapshot });

/**
 * Keeps every cached series and the snapshot current from the server's stream: each sample is
 * appended to the series it belongs to (dropping points older than the range) and written into
 * the snapshot. Idle when `live` is false. The stream starts with the newest sample, so a
 * reconnect needs no replay.
 */
export function useMetricStream(live: boolean) {
  const client = useQueryClient();
  const onSample = useCallback(
    ({ time, values }: MetricSample) => {
      for (const [queryKey, data] of client.getQueriesData<SeriesData>({
        queryKey: ['metrics', 'series'],
      })) {
        const [, , name, range] = queryKey as [string, string, string, Range];
        const next: SeriesData = { ...data };
        for (const [key, v] of Object.entries(values)) {
          if (key.startsWith(name)) {
            next[key] = [
              ...(next[key] ?? []).filter((p) => p.t > time - RANGE_MS[range]),
              { t: time, v },
            ];
          }
        }
        client.setQueryData(queryKey, next);
      }
      client.setQueryData(['metrics', 'snapshot'], values);
    },
    [client]
  );
  return useEventSourceJson<MetricSample>(live ? STREAM_URL : null, onSample);
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
