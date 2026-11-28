// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { ArcGauge } from '@/components/dashboard/Gauges';
import { Panel } from '@/components/dashboard/Tile';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { useMe } from '@/lib/identity';
import { getDrain } from '../api/drain';
import { getLimits, limitText } from '../api/scheduler';
import { PanelState } from './PanelState';
import { tagOf } from './format';

const LIMITS_KEY = ['control', 'scheduler', 'limits'];
const DRAIN_KEY = ['control', 'scheduler', 'drain'];

/** Running against the limit, the waiting queue and the refused starts. Read-only: the limits are set under Configuration. */
export function CapacityPanel({ live }: { live: boolean }) {
  const limits = useQuery({
    queryKey: LIMITS_KEY,
    queryFn: getLimits,
    refetchInterval: live ? 5000 : false,
  });
  const drain = useQuery({
    queryKey: DRAIN_KEY,
    queryFn: getDrain,
    refetchInterval: live ? 5000 : false,
  });
  const snapshot = useMetricSnapshot().data;
  const me = useMe();

  const data = limits.data;
  const max = data?.maxConcurrent ?? 0;
  const rejected = Object.entries(snapshot ?? {})
    .filter(([key]) => key.startsWith('yukta.executions.rejected'))
    .map(([key, rate]) => ({
      reason: tagOf(key, 'reason') ?? key,
      perMinute: rate * 60,
    }))
    .filter((r) => r.perMinute > 0);

  return (
    <Panel
      title="Capacity"
      info="How many runs the server executes at once against its limit, how many wait in the queue, and why new starts were refused. Always the whole server. Read-only: limits are changed under Configuration."
    >
      <PanelState
        isPending={limits.isPending}
        error={limits.error}
        empty={!data}
        emptyText="No scheduler"
        onRetry={() => void limits.refetch()}
      >
        {data && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-4">
              <ArcGauge
                label="Running of limit"
                value={data.running}
                max={max > 0 ? max : Math.max(1, data.running)}
                tone={max > 0 && data.running >= max ? 'attention' : 'primary'}
              />
              <dl className="text-body-sm text-on-surface-variant flex min-w-0 flex-col">
                <dt>Running</dt>
                <dd className="num text-on-surface">
                  {data.running} of {limitText(max)}
                </dd>
                <dt>Waiting</dt>
                <dd className="num text-on-surface">
                  {data.queued} of {data.queueCapacity}
                </dd>
                <dt>One workflow</dt>
                <dd className="num text-on-surface">
                  at most {limitText(data.maxPerWorkflow).toLowerCase()}
                </dd>
              </dl>
            </div>
            {drain.data?.draining && (
              <p className="text-body-sm bg-warning/15 text-on-surface self-start rounded-full px-2 py-0.5">
                Draining: new starts are refused
                {drain.data.idle ? ' (idle)' : ', running work finishes'}
              </p>
            )}
            {rejected.length > 0 && (
              <ul
                aria-label="Refused starts per minute"
                className="text-body-sm flex flex-wrap gap-2"
              >
                {rejected.map((r) => (
                  <li
                    key={r.reason}
                    className="bg-muted rounded-full px-2 py-0.5 tabular-nums"
                  >
                    {r.reason}: {r.perMinute.toFixed(1)}/min
                  </li>
                ))}
              </ul>
            )}
            {(!me || me.permissions.changeLimits) && (
              <Link
                to="/config?tab=capacity"
                className="text-body-sm text-primary self-start hover:underline"
              >
                Change limits in Configuration
              </Link>
            )}
          </div>
        )}
      </PanelState>
    </Panel>
  );
}
