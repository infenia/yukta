// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { KpiTile } from '@/components/dashboard/KpiTile';
import { RingProgress } from '@/components/dashboard/Gauges';
import {
  useHeatmap,
  useMetricSeries,
  useMetricSnapshot,
} from '@/features/metrics/hooks';
import { GLOBAL, type Scope } from '@/features/metrics/api';
import { inScope } from './scopes';
import { getAllApprovals } from '@/features/overview/api';
import { useWorkers } from '../api/workers';
import { getLimits } from '../api/scheduler';
import {
  addUp,
  formatChange,
  formatMs,
  integrate,
  percentChange,
  slowerIsWorse,
  successRate,
} from './format';

/** The approvals read for the tile; a full page reads as "50+". */
const APPROVALS_LIMIT = 50;

/** The approvals waiting in the scope as tile text; a full page reads as "50+". */
function useWaitingText(scope: Scope, live: boolean) {
  const approvals = useQuery({
    queryKey: ['control', 'approvals', APPROVALS_LIMIT],
    queryFn: () => getAllApprovals(APPROVALS_LIMIT),
    refetchInterval: live ? 10_000 : false,
  });
  if (approvals.isError) return '–';
  const all = approvals.data ?? [];
  const n = all.filter((a) => inScope(scope, a)).length;
  return `${n}${all.length >= APPROVALS_LIMIT ? '+' : ''}`;
}

/** Model spend over the last hour; model meters have no session or workflow tag, so always the server's. */
function useLlmSpend() {
  const cost = useMetricSeries('llm.cost', '1h').data;
  const points = Object.entries(cost ?? {})
    .filter(([key]) => key.endsWith('|rate'))
    .map(([, p]) => p);
  return points.length > 0 ? integrate(addUp(points)) : null;
}

/**
 * The headline numbers: throughput, success rate, latency, waiting approvals, load, workers and
 * model spend. Throughput and p95 carry their 1 h trend and the change over that hour.
 */
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
  const waitingText = useWaitingText(scope, live);
  const spend = useLlmSpend();
  const workers = useWorkers(live).data ?? [];

  const dayRuns = (day ?? []).reduce((n, c) => n + c.runs, 0);
  const dayFailed = (day ?? []).reduce((n, c) => n + c.failed, 0);
  const rate = successRate(dayRuns, dayFailed);
  const slots = workers.reduce((n, w) => n + w.capacity, 0);
  const used = workers.reduce((n, w) => n + w.usedSlots, 0);
  const runsTrend = finished?.['runs.finished'] ?? [];
  const p95Trend = p95?.['duration.p95'] ?? [];
  const runsChange = percentChange(runsTrend);
  const p95Change = percentChange(p95Trend);

  // these tiles read the whole server; say so when the view is scoped
  const scoped = (label: string) =>
    scope === GLOBAL ? label : `${label} (whole server)`;

  return (
    <div
      className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-7"
      data-testid="command-strip"
    >
      <KpiTile
        label="Runs / min (1 h)"
        value={((snapshot?.['runs.finished'] ?? 0) / 60).toFixed(1)}
        delta={formatChange(runsChange)}
        series={runsTrend.map((p) => p.v)}
      />
      <div className="border-border bg-card flex min-w-0 items-center gap-3 rounded-xl border p-3">
        {/* the ring is 80 px on its own; a tile on a phone has room for 56 */}
        <div className="shrink-0 [&>div]:size-14 lg:[&>div]:size-16">
          <RingProgress
            label="Success rate, 24 h"
            value={rate ?? 0}
            max={100}
            tone={rate !== null && rate < 90 ? 'danger' : 'success'}
          />
        </div>
        <div className="flex min-w-0 flex-col">
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
        delta={formatChange(p95Change)}
        deltaTone={slowerIsWorse(p95Change)}
        series={p95Trend.map((p) => p.v)}
      />
      <Link
        to="/"
        className="focus-visible:ring-ring flex min-w-0 rounded-xl no-underline focus-visible:ring-2"
        aria-label={`Approvals waiting: ${waitingText}. Open the overview to answer them`}
      >
        <div className="flex-1 [&>div]:h-full">
          <KpiTile label="Approvals waiting" value={waitingText} />
        </div>
      </Link>
      <KpiTile
        label={scoped('Running / waiting')}
        value={limits ? `${limits.running} / ${limits.queued}` : '–'}
      />
      <KpiTile
        label={scoped('Worker slots in use')}
        value={workers.length === 0 ? '–' : `${used} / ${slots}`}
      />
      <KpiTile
        label={scoped('LLM spend (1 h)')}
        value={spend === null ? '–' : spend.toFixed(2)}
      />
    </div>
  );
}
