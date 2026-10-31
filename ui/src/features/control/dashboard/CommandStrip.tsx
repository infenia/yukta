// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { KpiTile } from '@/components/dashboard/KpiTile';
import { RingProgress } from '@/components/dashboard/Gauges';
import {
  useHeatmap,
  useMetricSeries,
  useMetricSnapshot,
} from '@/features/metrics/hooks';
import type { Scope } from '@/features/metrics/api';
import { useWorkers } from '../api/workers';
import { getLimits } from '../api/scheduler';
import { formatMs, successRate, sumOf } from './format';

/** The headline numbers: throughput, success rate, latency, load and spend, with 1 h trends. */
export function CommandStrip({ scope, live }: { scope: Scope; live: boolean }) {
  const snapshot = useMetricSnapshot(scope).data;
  const finished = useMetricSeries('runs.finished', '1h', scope).data;
  const p95 = useMetricSeries('duration.p95', '1h', scope).data;
  const day = useHeatmap('24h', scope, live).data;
  const limits = useQuery({
    queryKey: ['control', 'scheduler', 'limits'],
    queryFn: getLimits,
    refetchInterval: live ? 5000 : false,
  }).data;
  const workers = useWorkers().data ?? [];

  const dayRuns = (day ?? []).reduce((n, c) => n + c.runs, 0);
  const dayFailed = (day ?? []).reduce((n, c) => n + c.failed, 0);
  const rate = successRate(dayRuns, dayFailed);
  const slots = workers.reduce((n, w) => n + w.capacity, 0);
  const used = workers.reduce((n, w) => n + w.usedSlots, 0);
  const tokensPerMinute = sumOf(snapshot, 'llm.tokens', '|rate') * 60;

  return (
    <div
      className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6"
      data-testid="command-strip"
    >
      <KpiTile
        label="Runs / min (1 h)"
        value={((snapshot?.['runs.finished'] ?? 0) / 60).toFixed(1)}
        series={(finished?.['runs.finished'] ?? []).map((p) => p.v)}
      />
      <div className="flex items-center gap-3 rounded-xl border border-border bg-card p-3">
        <div className="w-16 shrink-0">
          <RingProgress
            label="Success rate, 24 h"
            value={rate ?? 0}
            max={100}
            tone={rate !== null && rate < 90 ? 'danger' : 'success'}
          />
        </div>
        <div className="flex flex-col">
          <span className="text-label-md text-on-surface-variant">
            Success rate (24 h)
          </span>
          <span className="text-headline-md num text-on-surface">
            {rate === null ? '–' : `${rate}%`}
          </span>
        </div>
      </div>
      <KpiTile
        label="p95 duration (1 h)"
        value={
          snapshot?.['duration.p95'] === undefined
            ? '–'
            : formatMs(snapshot['duration.p95'])
        }
        series={(p95?.['duration.p95'] ?? []).map((p) => p.v)}
      />
      <KpiTile
        label="Running / waiting"
        value={limits ? `${limits.running} / ${limits.queued}` : '–'}
      />
      <KpiTile
        label="Worker slots in use"
        value={workers.length === 0 ? '–' : `${used} / ${slots}`}
      />
      <KpiTile
        label="LLM tokens / min"
        value={
          tokensPerMinute > 0 ? Math.round(tokensPerMinute).toString() : '–'
        }
      />
    </div>
  );
}
