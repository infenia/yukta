// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import { HeatStrip } from '@/components/dashboard/Strips';
import { KpiTile } from '@/components/dashboard/KpiTile';
import { useFallbackPoll } from '@/features/control/context/ControlEventsContext';
import { getSupervisorDecisions } from '../api/supervisors';
import { getSupervisionSummary } from '../api/supervision';
import { useAttachments, useSupervisors } from '../hooks/useSupervisorTypes';

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
    <section aria-label="Supervisors" className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-headline-md text-on-surface">Supervisors</h2>
        <Link
          to="/supervisors"
          className={buttonVariants({ variant: 'outline', size: 'sm' })}
        >
          Open supervisors
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <Link to="/supervisors" aria-label="Supervisors tile">
          <KpiTile label="Supervisors" value={na(supervisors.data?.length)} />
        </Link>
        <KpiTile label="Assignments" value={na(attachments.data?.length)} />
        <Link
          to="/supervisors?supervisor=default"
          aria-label="Under direct control tile"
        >
          <KpiTile
            label="Under direct control"
            value={na(summary.data?.unowned)}
          />
        </Link>
        <KpiTile
          label="Applied"
          value={decisions.data ? String(count('applied')) : '–'}
        />
        <KpiTile
          label="Refused"
          value={decisions.data ? String(count('rejected')) : '–'}
        />
      </div>
      <p className="text-body-sm text-on-surface-variant">
        Applied and Refused count the newest {list.length} decisions the server
        still holds.
        {summary.data &&
          ` ${plural(summary.data.unowned, 'workflow', 'workflows')} nobody owns.`}
      </p>
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
          <ul className="flex flex-wrap gap-2">
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
    </section>
  );
}
