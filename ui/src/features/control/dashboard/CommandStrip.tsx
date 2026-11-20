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
import type { Scope } from '@/features/metrics/api';
import { inScope } from './scopes';
import { getOpenInputs } from '@/features/workflow/api/inputs';
import { getLimits, limitText } from '../api/scheduler';
import {
  formatChange,
  formatMs,
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
    queryFn: () => getOpenInputs(APPROVALS_LIMIT),
    refetchInterval: live ? 10_000 : false,
  });
  if (approvals.isError) return '–';
  const all = approvals.data ?? [];
  const n = all.filter((a) => inScope(scope, a)).length;
  return `${n}${all.length >= APPROVALS_LIMIT ? '+' : ''}`;
}

/** Said under the engine tile: what the number is and is not. */
const ENGINE_TITLE =
  "Engine overhead: how long a message waits between taking its node's turn and the plugin being called. It leaves out the plugin's own time, so a slow build does not show here.";

/**
 * The headline numbers, six dense tiles: what is running and waiting, how runs ended, the engine's
 * own overhead (not the plugins' time) and the inputs waiting for a person.
 */
export function CommandStrip({ scope, live }: { scope: Scope; live: boolean }) {
  const snapshot = useMetricSnapshot(scope).data;
  const handoff = useMetricSeries('overhead.handoff.p95', '1h', scope).data;
  const day = useHeatmap('24h', scope, live).data;
  const limits = useQuery({
    queryKey: ['control', 'scheduler', 'limits'],
    queryFn: getLimits,
    refetchInterval: live ? 5000 : false,
  }).data;
  const waitingText = useWaitingText(scope, live);

  const dayRuns = (day ?? []).reduce((n, c) => n + c.runs, 0);
  const dayFailed = (day ?? []).reduce((n, c) => n + c.failed, 0);
  const rate = successRate(dayRuns, dayFailed);
  const trend = handoff?.['overhead.handoff.p95'] ?? [];
  const change = percentChange(trend);
  const p95 = snapshot?.['overhead.handoff.p95'];
  const start = snapshot?.['overhead.start.p95'];
  const failed = snapshot?.['runs.failed'] ?? 0;
  const finished = snapshot?.['runs.finished'] ?? 0;

  // running and waiting read the whole server's scheduler; say so when the view is scoped
  const whole = scope === 'global' ? '' : ' (whole server)';

  return (
    <div
      className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6"
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
      />
      <KpiTile
        compact
        label={`Queued${whole}`}
        value={limits ? String(limits.queued) : '–'}
        hint={limits ? `holds ${limits.queueCapacity}` : undefined}
      />
      <KpiTile
        compact
        label="Success (24 h)"
        value={rate === null ? '–' : `${rate}%`}
        adornment={
          <div className="size-9 shrink-0 [&>div]:size-9">
            <RingProgress
              label="Success rate, 24 h"
              value={rate ?? 0}
              max={100}
              tone={rate !== null && rate < 90 ? 'danger' : 'success'}
            />
          </div>
        }
      />
      <KpiTile
        compact
        label="Failed (1 h)"
        value={String(failed)}
        hint={`of ${finished} runs`}
      />
      <KpiTile
        compact
        title={ENGINE_TITLE}
        label="Engine p95 (1 h)"
        value={p95 === undefined ? '–' : formatMs(p95)}
        delta={formatChange(change)}
        deltaTone={slowerIsWorse(change)}
        hint={
          start === undefined
            ? 'hand-off'
            : `hand-off · start ${formatMs(start)}`
        }
        series={trend.map((p) => p.v)}
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
        />
      </Link>
    </div>
  );
}
