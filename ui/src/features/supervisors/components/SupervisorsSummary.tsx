// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import { HeatStrip } from '@/components/dashboard/Strips';
import { Panel } from '@/components/dashboard/Tile';
import { useFallbackPoll } from '@/features/control/context/ControlEventsContext';
import { getSupervisorDecisions } from '../api/supervisors';
import { getSupervisionSummary } from '../api/supervision';
import { useAttachments, useSupervisors } from '../hooks/useSupervisorTypes';
import { Tip } from '@/components/ui/tooltip';

const BUCKETS = 48;
const TOP = 5;
const plural = (n: number, one: string, many: string) =>
  `${n} ${n === 1 ? one : many}`;

/**
 * Control page: how many supervisors there are, what they own, who is steering by hand, and a heat
 * strip of their newest decisions. The Supervisors page keeps the list and the detail.
 */
export function SupervisorsSummary() {
  // keys under control/supervisors: a control event on the stream refreshes them
  const poll = useFallbackPoll();
  const supervisors = useSupervisors();
  const attachments = useAttachments();
  const decisions = useQuery({
    queryKey: ['control', 'supervisors', 'decisions'],
    queryFn: () => getSupervisorDecisions(undefined, 500),
    refetchInterval: poll,
  });
  const summary = useQuery({
    queryKey: ['control', 'supervisors', 'summary'],
    queryFn: getSupervisionSummary,
    refetchInterval: poll,
  });
  const list = decisions.data ?? [];
  const count = (outcome: string) =>
    list.filter((d) => d.outcome === outcome).length;
  const na = (value: number | undefined) =>
    value === undefined ? '–' : String(value);

  // `dataUpdatedAt` is when the decisions were fetched: a stable "now" that is not read during render
  const now = decisions.dataUpdatedAt;
  const times = list.map((d) => Date.parse(d.time));
  const from = times.length ? Math.min(...times) : now;
  const step = Math.max(1, (now - from) / BUCKETS);
  const buckets = Array.from({ length: BUCKETS }, () => 0);
  times.forEach((t) => {
    buckets[Math.min(BUCKETS - 1, Math.floor((t - from) / step))]++;
  });
  const busiest = Math.max(1, ...buckets);
  const hours = (now - from) / 3_600_000;
  const perId = new Map<string, number>();
  list.forEach((d) =>
    perId.set(d.supervisorId, (perId.get(d.supervisorId) ?? 0) + 1)
  );
  const perSupervisor = [...perId].sort((a, b) => b[1] - a[1]).slice(0, TOP);

  return (
    <Panel
      title="Supervisors"
      action={
        <Link
          to="/supervisors"
          className={buttonVariants({ variant: 'ghost', size: 'sm' })}
        >
          Open supervisors
        </Link>
      }
    >
      <dl className="grid grid-cols-2 gap-x-3 gap-y-2 sm:grid-cols-4">
        <Stat
          label="Supervisors"
          value={na(supervisors.data?.length)}
          to="/supervisors"
          aria="Supervisors tile"
        />
        <Stat label="Assignments" value={na(attachments.data?.length)} />
        <Stat
          label="Direct control"
          value={na(summary.data?.unowned)}
          to="/supervisors?supervisor=default"
          aria="Under direct control tile"
          title={
            summary.data
              ? `${plural(summary.data.unowned, 'workflow', 'workflows')} nobody supervises`
              : undefined
          }
        />
        <Stat
          label="Applied / refused"
          value={
            decisions.data ? `${count('applied')} / ${count('rejected')}` : '–'
          }
          title={`Of the newest ${list.length} decisions the server still holds`}
        />
      </dl>
      {list.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">
          No supervisor decisions yet.
        </p>
      ) : (
        <>
          <HeatStrip
            columns={BUCKETS}
            label={`Supervisor decisions over the last ${hours.toFixed(1)} hours`}
            cells={buckets.map((n, i) => ({
              label: `${new Date(from + i * step).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}: ${plural(n, 'decision', 'decisions')}`,
              volume: n / busiest,
            }))}
          />
          <p className="text-body-sm text-on-surface-variant">
            Last {list.length} decisions · {hours.toFixed(1)} h
          </p>
          <ul className="flex flex-wrap gap-x-3 gap-y-1">
            {perSupervisor.map(([id, n]) => (
              <li key={id}>
                <Link
                  to={`/supervisors?supervisor=${encodeURIComponent(id)}`}
                  className="text-label-md text-primary hover:underline"
                >
                  {id}
                </Link>{' '}
                <span className="text-body-sm text-on-surface-variant">
                  {n}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </Panel>
  );
}

/** One number of the card; a link when it has somewhere to go. */
function Stat({
  label,
  value,
  to,
  aria,
  title,
}: {
  label: string;
  value: string;
  to?: string;
  aria?: string;
  title?: string;
}) {
  const body = (
    <Tip label={title}>
      <div>
        <dt className="text-label-sm text-on-surface-variant">{label}</dt>
        <dd className="text-headline-md num text-on-surface">{value}</dd>
      </div>
    </Tip>
  );
  return to ? (
    <Link to={to} aria-label={aria}>
      {body}
    </Link>
  ) : (
    body
  );
}
