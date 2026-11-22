// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import { MultiSelect } from '@/components/ui/select';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { useSupervisorDecisions } from '../hooks/useSupervisorTypes';
import { groupDecisions, type DecisionGroup } from '../lib/activity';
import type { Decision } from '../types';
import { TimelineDot } from './ActionsTimeline';
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

/** The dot of a group: green when everything applied, grey for dry runs, yellow when waiting, orange otherwise. */
function toneOf(group: DecisionGroup) {
  const outcomes = new Set(group.items.map((d) => d.outcome));
  if (outcomes.size === 1 && outcomes.has('applied')) return 'success';
  if (outcomes.size === 1 && outcomes.has('dry-run')) return 'muted';
  if (outcomes.size === 1 && outcomes.has('awaiting-approval'))
    return 'warning';
  return 'attention';
}

/** The header of one group: when, why, who, on what, which command and how it went. */
function Group({ group }: { group: DecisionGroup }) {
  const [first] = group.items;
  const cause = causeOf(first.cause);
  const several = group.items.length > 1;
  const applied = group.items.filter((d) => d.outcome === 'applied').length;
  return (
    <li className="relative flex flex-col gap-1 py-2 pl-4">
      <TimelineDot tone={toneOf(group)} />
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
  values,
  onChange,
  options,
  any,
  name = (v: string) => v,
}: {
  label: string;
  values: string[];
  onChange: (next: string[]) => void;
  options: string[];
  any: string;
  name?: (value: string) => string;
}) {
  return (
    <MultiSelect
      ariaLabel={label}
      values={values}
      onValuesChange={onChange}
      placeholder={any}
      options={options.map((o) => ({ value: o, label: name(o) }))}
    />
  );
}

/**
 * What supervisors did, newest first: cause, supervisor, target, command, outcome; "on its own"
 * always with its reason, and a command for several targets with a result per target.
 */
export function Activity({ supervisor }: { supervisor?: string }) {
  const decisions = useSupervisorDecisions(supervisor);
  const { data, isPending, error, refetch } = decisions;
  const none = { supervisorId: [], cause: [], outcome: [], workflowId: [] };
  const [filters, setFilters] = useState<Record<string, string[]>>(none);
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
  const set = (key: string) => (values: string[]) =>
    setFilters({ ...filters, [key]: values });
  // a decision matches when a filter is empty or lists its value
  const shown = data.filter((d) =>
    Object.entries(filters).every(
      ([key, values]) =>
        values.length === 0 ||
        values.includes(String(d[key as keyof Decision] ?? ''))
    )
  );
  return (
    <div className="flex flex-col gap-3">
      <StaleNotice query={decisions} />
      <p className="text-body-md text-muted-foreground max-w-prose">
        What {supervisor ?? 'every supervisor'} did since the server started,
        newest first.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        {!supervisor && (
          <Filter
            label="Supervisor"
            any="Any supervisor"
            values={filters.supervisorId}
            onChange={set('supervisorId')}
            options={distinct(data.map((d) => d.supervisorId))}
          />
        )}
        <Filter
          label="Cause"
          any="Any cause"
          values={filters.cause}
          onChange={set('cause')}
          options={distinct(data.map((d) => d.cause))}
          name={(c) => causeOf(c).label}
        />
        <Filter
          label="Outcome"
          any="Any outcome"
          values={filters.outcome}
          onChange={set('outcome')}
          options={distinct(data.map((d) => d.outcome))}
        />
        <Filter
          label="Workflow"
          any="Any workflow"
          values={filters.workflowId}
          onChange={set('workflowId')}
          options={distinct(data.map((d) => d.workflowId))}
        />
        <span className="text-label-sm text-on-surface-variant ml-auto tabular-nums">
          {shown.length} shown
        </span>
      </div>
      {shown.length === 0 ? (
        <StateMessage
          compact
          icon="search"
          title="Nothing matches these filters."
        >
          <Button variant="ghost" size="sm" onClick={() => setFilters(none)}>
            Clear filters
          </Button>
        </StateMessage>
      ) : (
        <div className="border-border bg-muted/30 rounded-xl border p-3">
          <ol
            aria-label="Activity"
            className="border-border text-body-md ml-1 flex flex-col border-l"
          >
            {groupDecisions(shown).map((group) => (
              <Group key={group.id} group={group} />
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}
