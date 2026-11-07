// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Card } from '@/components/ui/card';
import { useFallbackPoll } from '@/features/control/context/ControlEventsContext';
import {
  getRunningSupervisors,
  getSupervisorDecisions,
} from '../api/supervisors';

const HOUR = 3_600_000;
const WINDOW = 10 * 60_000;
const CONFLICTS = new Set(['arbitrated', 'coalesced', 'flapping-blocked']);

/** Control page summary: how many supervisors run, conflicts in the last hour, decisions per minute. */
export function SupervisorsTile() {
  // keys under control/supervisors: a control event on the stream refreshes the tile
  const poll = useFallbackPoll();
  const running = useQuery({
    queryKey: ['control', 'supervisors', 'running'],
    queryFn: getRunningSupervisors,
    refetchInterval: poll,
  });
  const decisions = useQuery({
    queryKey: ['control', 'supervisors', 'decisions'],
    queryFn: () => getSupervisorDecisions(undefined, 500),
    refetchInterval: poll,
  });
  // `dataUpdatedAt` is when the decisions were fetched: a stable "now" that is not read during render
  const now = decisions.dataUpdatedAt;
  const lastHour = (decisions.data ?? []).filter(
    (d) => now - Date.parse(d.time) <= HOUR
  );
  const conflicts = lastHour.filter((d) => CONFLICTS.has(d.outcome)).length;
  const recent = lastHour.filter((d) => now - Date.parse(d.time) <= WINDOW);
  const perMinute = recent.length / (WINDOW / 60_000);
  const active = (running.data ?? []).filter(
    (s) => s.kind === 'SUPERVISOR'
  ).length;

  return (
    <Link to="/supervisors" aria-label="Supervisors">
      <Card interactive className="p-4">
        <p className="text-overline text-on-surface-variant">Supervisors</p>
        <p className="text-headline-md">{running.data ? active : '–'} active</p>
        <p className="text-body-sm text-on-surface-variant">
          {decisions.data
            ? `${conflicts} conflicts in the last hour · ${perMinute.toFixed(1)} decisions/min`
            : 'loading decisions'}
        </p>
      </Card>
    </Link>
  );
}
