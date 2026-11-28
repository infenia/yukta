// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { KpiTile } from '@/components/dashboard/KpiTile';
import {
  useHeatmap,
  useMetricSeries,
  useMetricSnapshot,
} from '@/features/metrics/hooks';
import type { Scope } from '@/features/metrics/api';
import { inScope } from './scopes';
import { MetricDrawer, type DrillSpec } from './MetricDrawer';
import { useNow } from './useNow';
import { getOpenInputs } from '@/features/workflow/api/inputs';
import { getLimits, limitText } from '../api/scheduler';
import {
  failedHours,
  fillSeries,
  formatChange,
  failedPoints,
  formatMs,
  percentChange,
  slowerIsWorse,
  successPoints,
  successRate,
} from './format';

/** The approvals read for the tile; a full page reads as "50+". */
const APPROVALS_LIMIT = 50;

/** The approvals waiting in the scope as tile text; a full page reads as "50+". */
function useWaitingText(scope: Scope, live: boolean) {
  const approvals = useQuery({
    queryKey: ['control', 'approvals', APPROVALS_LIMIT],
    queryFn: () => getOpenInputs(APPROVALS_LIMIT),
    refetchInterval: live ? 10_000 : false,
  });
  const all = approvals.data ?? [];
  const n = all.filter((a) => inScope(scope, a)).length;
  const text = approvals.isError
    ? '–'
    : `${n}${all.length >= APPROVALS_LIMIT ? '+' : ''}`;
  // the server keeps no history of waiting inputs, so the graph is the counts seen while this
  // page is open, one per fetch
  const [samples, setSamples] = useState<number[]>([]);
  const [sampledAt, setSampledAt] = useState(0);
  if (approvals.dataUpdatedAt !== sampledAt) {
    setSampledAt(approvals.dataUpdatedAt);
    setSamples((previous) => [...previous, n].slice(-SAMPLES));
  }
  return { text, samples };
}

const MINUTE_MS = 60_000;
const HALF_HOUR_MS = 30 * MINUTE_MS;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/** Counts of waiting inputs kept for the tile's graph. */
const SAMPLES = 60;

/** Said behind the engine tile's "i": what the number is and is not. */
const ENGINE_INFO =
  "Engine overhead: how long a message waits between taking its node's turn and the plugin being called. It leaves out the plugin's own time, so a slow build does not show here.";

/**
 * The headline numbers, seven dense tiles: what is running and waiting and for how long, how runs
 * ended, the engine's own overhead (not the plugins' time) and the inputs waiting for a person.
 */
export function CommandStrip({ scope, live }: { scope: Scope; live: boolean }) {
  const snapshot = useMetricSnapshot(scope).data;
  const start = useMetricSeries('overhead.start.p95', '1h', scope).data;
  const day = useHeatmap('24h', scope, live).data;
  const limits = useQuery({
    queryKey: ['control', 'scheduler', 'limits'],
    queryFn: getLimits,
    refetchInterval: live ? 5000 : false,
  }).data;
  const { text: waitingText, samples: waitingSamples } = useWaitingText(
    scope,
    live
  );
  // running and waiting are gauges of the whole server, so their history is the global series
  const scheduler = useMetricSeries('yukta.executions.', '1h').data;
  const runningSeries = scheduler?.['yukta.executions.running'] ?? [];
  const queuedSeries = scheduler?.['yukta.executions.queued'] ?? [];
  const waitSeries =
    useMetricSeries('queue.wait', '1h', scope).data?.['queue.wait.p95'] ?? [];
  const wait =
    waitSeries.length > 0 ? waitSeries[waitSeries.length - 1].v : undefined;
  const now = useNow();
  const [drill, setDrill] = useState<DrillSpec | null>(null);

  const dayRuns = (day ?? []).reduce((n, c) => n + c.runs, 0);
  const dayFailed = (day ?? []).reduce((n, c) => n + c.failed, 0);
  const rate = successRate(dayRuns, dayFailed);
  const trend = start?.['overhead.start.p95'] ?? [];
  const successSeries = successPoints(day ?? []);
  // every minute of the last hour, a quiet minute keeping the value before it
  const hourly = (points: { t: number; v: number }[]) =>
    fillSeries(points, now, HOUR_MS, MINUTE_MS, 0);
  const failedSeries = failedPoints(day ?? [], now);
  const hours = failedHours(day ?? [], now);
  const failedChange = hours.last - hours.previous;
  const change = percentChange(trend);
  const p95 = snapshot?.['overhead.start.p95'];
  const handoff = snapshot?.['overhead.handoff.p95'];
  const failed = snapshot?.['runs.failed'] ?? 0;
  const finished = snapshot?.['runs.finished'] ?? 0;

  // running and waiting read the whole server's scheduler; say so when the view is scoped
  const whole = scope === 'global' ? '' : ' (whole server)';

  return (
    <>
      <div
        className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-7"
        data-testid="command-strip"
      >
        <KpiTile
          compact
          label={`Running${whole}`}
          value={limits ? String(limits.running) : '–'}
          hint={
            limits
              ? `of ${limitText(limits.maxConcurrent).toLowerCase()}`
              : undefined
          }
          info="Runs executing now, against the server's concurrent-run limit. Always the whole server."
          series={hourly(runningSeries)}
          onDrill={() =>
            setDrill({
              title: 'Running',
              window: 'last hour',
              info: "Runs executing now, against the server's concurrent-run limit.",
              points: runningSeries,
            })
          }
        />
        <KpiTile
          compact
          label={`Queued${whole}`}
          value={limits ? String(limits.queued) : '–'}
          hint={limits ? `holds ${limits.queueCapacity}` : undefined}
          info="Runs waiting for a free slot. When the queue is full, new starts are refused. Always the whole server."
          series={hourly(queuedSeries)}
          onDrill={() =>
            setDrill({
              title: 'Queued',
              window: 'last hour',
              info: 'Runs waiting for a free slot.',
              points: queuedSeries,
            })
          }
        />
        <KpiTile
          compact
          label="Queue wait p95 (1 h)"
          value={wait === undefined ? '–' : formatMs(wait)}
          hint="how long starts waited for a slot"
          info="How long runs that had to wait for a free slot waited, slowest 1 in 20, over the last hour. A rising number means the concurrent-run limit is too low for the load."
          series={hourly(waitSeries)}
          onDrill={() =>
            setDrill({
              title: 'Queue wait p95',
              window: 'last hour',
              info: 'How long starts waited for a free slot.',
              points: waitSeries,
              format: formatMs,
            })
          }
        />
        <KpiTile
          compact
          label="Success (24 h)"
          value={rate === null ? '–' : `${rate}%`}
          hint={rate === null ? undefined : `of ${dayRuns} runs`}
          tone={rate !== null && rate < 90 ? 'danger' : 'success'}
          info="The share of runs that finished without failing in the last 24 hours."
          series={fillSeries(successSeries, now, DAY_MS, HALF_HOUR_MS)}
          onDrill={() =>
            setDrill({
              title: 'Success rate',
              window: 'last 24 hours, by half hour',
              info: 'The share of runs that finished without failing.',
              points: successSeries,
              format: (v) => `${v}%`,
            })
          }
        />
        <KpiTile
          compact
          label="Failed (1 h)"
          value={String(failed)}
          hint={`of ${finished} runs`}
          delta={
            hours.last + hours.previous > 0
              ? `${failedChange > 0 ? '+' : ''}${failedChange} vs hour before`
              : undefined
          }
          deltaTone={
            failedChange > 0
              ? 'danger'
              : failedChange < 0
                ? 'success'
                : 'neutral'
          }
          tone="danger"
          info="Runs that failed in the last hour, out of all runs that finished in that hour."
          series={failedSeries.map((p) => p.v)}
          onDrill={() =>
            setDrill({
              title: 'Failed runs',
              window: 'last hour, by five minutes',
              info: 'Runs that failed.',
              points: failedSeries,
            })
          }
        />
        <KpiTile
          compact
          info={ENGINE_INFO}
          label="Engine p95 (1 h)"
          value={p95 === undefined ? '–' : formatMs(p95)}
          delta={formatChange(change)}
          deltaTone={slowerIsWorse(change)}
          hint={
            handoff === undefined
              ? 'request to first plugin'
              : `request to plugin · hand-off ${formatMs(handoff)}`
          }
          series={hourly(trend)}
          onDrill={() =>
            setDrill({
              title: 'Engine p95',
              window: 'last hour',
              info: ENGINE_INFO,
              points: trend,
              format: formatMs,
            })
          }
        />
        <Link
          to="/inbox"
          aria-label={`Inputs waiting: ${waitingText}. Open the inbox.`}
          className="flex min-w-0 [&>div]:flex-1"
        >
          <KpiTile
            compact
            label="Inputs waiting"
            value={waitingText}
            hint="open the inbox"
            series={waitingSamples}
          />
        </Link>
      </div>
      {drill && <MetricDrawer spec={drill} onClose={() => setDrill(null)} />}
    </>
  );
}
