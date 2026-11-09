// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { StateMessage } from '@/components/common/StateMessage';
import { NativeSelect } from '@/components/ui/native-select';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { useSupervisorDecisions } from '../hooks/useSupervisorTypes';
import { groupDecisions, type DecisionGroup } from '../lib/activity';
import type { Decision } from '../types';
import { StaleNotice } from './StaleNotice';

const CAUSES: Record<string, { label: string; icon: string }> = {
  event: { label: 'event', icon: 'notifications' },
  answers: { label: 'answered', icon: 'person' },
  tick: { label: 'tick', icon: 'schedule' },
  initiative: { label: 'on its own', icon: 'smart_toy' },
};

const causeOf = (cause: string | null | undefined) =>
  CAUSES[cause ?? ''] ?? { label: cause ?? 'no cause given', icon: 'info' };

const OUTCOMES: Record<string, { icon: string; tone: string }> = {
  applied: { icon: 'check_circle', tone: 'text-success' },
  'dry-run': { icon: 'filter_alt', tone: 'text-muted-foreground' },
  'awaiting-approval': { icon: 'hourglass_empty', tone: 'text-warning' },
};

/** An outcome as an icon plus its word; a refusal of any kind in the error colour. */
function Outcome({
  outcome,
  reason,
}: {
  outcome: string;
  reason?: string | null;
}) {
  const look = OUTCOMES[outcome] ?? { icon: 'block', tone: 'text-error' };
  return (
    <span className={`inline-flex items-center gap-1 ${look.tone}`}>
      <span aria-hidden="true" className="material-symbols-outlined text-sm">
        {look.icon}
      </span>
      {outcome}
      {reason && outcome !== 'applied' ? `: ${reason}` : ''}
    </span>
  );
}

const shortRun = (id: string) => `run ${id.slice(0, 8)}`;

const targetOf = (d: Decision) =>
  [d.workflowId, d.nodeId ?? shortRun(d.executionId)]
    .filter(Boolean)
    .join(' · ');

const commandOf = (command: string) => command.replace(/Command$/, '');

/** The header of one group: when, why, who, on what, which command and how it went. */
function Group({ group }: { group: DecisionGroup }) {
  const [first] = group.items;
  const cause = causeOf(first.cause);
  const several = group.items.length > 1;
  const applied = group.items.filter((d) => d.outcome === 'applied').length;
  return (
    <li className="border-border flex flex-col gap-1 border-b px-3 py-2 last:border-b-0">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-muted-foreground tabular-nums">
          {formatDateTime(first.time)}
        </span>
        <span className="inline-flex items-center gap-1">
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-sm"
          >
            {cause.icon}
          </span>
          {cause.label}
        </span>
        <span>
          <b>{first.supervisorId}</b> →{' '}
          {several
            ? `${group.items.length} ${first.nodeId ? 'nodes' : 'runs'}`
            : targetOf(first)}
        </span>
        <span className="text-code-sm">{commandOf(first.command)}</span>
        {several && applied !== group.items.length ? (
          <span className="text-warning">
            {applied} of {group.items.length} applied
          </span>
        ) : (
          <Outcome
            outcome={first.outcome}
            reason={several ? null : first.reason}
          />
        )}
      </div>
      {(first.because || first.cause === 'initiative') && (
        <p className="text-body-sm">Reason: {first.because ?? 'none given'}</p>
      )}
      {several && (
        <ul aria-label="Per target" className="text-body-sm flex flex-col pl-4">
          {group.items.map((d, i) => (
            <li key={i} className="flex flex-wrap gap-x-3">
              <span className="text-code-sm">{targetOf(d)}</span>
              <Outcome outcome={d.outcome} reason={d.reason} />
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

const distinct = (values: (string | null | undefined)[]) =>
  [...new Set(values.filter((v): v is string => !!v))].sort();

function Filter({
  label,
  value,
  onChange,
  options,
  all,
  name = (v: string) => v,
}: {
  label: string;
  value: string;
  onChange: (next: string) => void;
  options: string[];
  all: string;
  name?: (value: string) => string;
}) {
  return (
    <NativeSelect
      aria-label={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      <option value="">{all}</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {name(o)}
        </option>
      ))}
    </NativeSelect>
  );
}

/**
 * What supervisors did, newest first: cause, supervisor, target, command, outcome; "on its own"
 * always with its reason, and a command for several targets with a result per target.
 */
export function Activity({ supervisor }: { supervisor?: string }) {
  const decisions = useSupervisorDecisions(supervisor);
  const { data, isPending, error, refetch } = decisions;
  const [filters, setFilters] = useState({
    supervisorId: '',
    cause: '',
    outcome: '',
    workflowId: '',
  });
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  // pending is handled above, so no data means the first fetch failed
  if (!data) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load the activity."
        onRetry={() => void refetch()}
      >
        {error?.message}
      </StateMessage>
    );
  }
  if (data.length === 0) {
    return (
      <StateMessage icon="history" title="No activity yet">
        Commands sent by supervisors, and the ones refused, appear here.
      </StateMessage>
    );
  }
  const set = (key: keyof typeof filters) => (value: string) =>
    setFilters({ ...filters, [key]: value });
  const shown = data.filter((d) =>
    (Object.keys(filters) as (keyof typeof filters)[]).every(
      (key) => !filters[key] || d[key] === filters[key]
    )
  );
  return (
    <div className="flex flex-col gap-3">
      <StaleNotice query={decisions} />
      <p className="text-body-md text-muted-foreground max-w-prose">
        What {supervisor ?? 'every supervisor'} did since the server started:
        the cause, the target, the command and its outcome. “On its own” always
        shows its reason. A command for several targets shows a result per
        target.
      </p>
      <div className="flex flex-wrap gap-2">
        {!supervisor && (
          <Filter
            label="Supervisor"
            all="All supervisors"
            value={filters.supervisorId}
            onChange={set('supervisorId')}
            options={distinct(data.map((d) => d.supervisorId))}
          />
        )}
        <Filter
          label="Cause"
          all="All causes"
          value={filters.cause}
          onChange={set('cause')}
          options={distinct(data.map((d) => d.cause))}
          name={(c) => causeOf(c).label}
        />
        <Filter
          label="Outcome"
          all="All outcomes"
          value={filters.outcome}
          onChange={set('outcome')}
          options={distinct(data.map((d) => d.outcome))}
        />
        <Filter
          label="Workflow"
          all="All workflows"
          value={filters.workflowId}
          onChange={set('workflowId')}
          options={distinct(data.map((d) => d.workflowId))}
        />
      </div>
      {shown.length === 0 ? (
        <StateMessage compact title="Nothing matches these filters." />
      ) : (
        <ul
          aria-label="Activity"
          className="border-border bg-card text-body-md shadow-card rounded-xl border"
        >
          {groupDecisions(shown).map((group) => (
            <Group key={group.id} group={group} />
          ))}
        </ul>
      )}
    </div>
  );
}
