// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { StateMessage } from '@/components/common/StateMessage';
import { Tip } from '@/components/ui/tooltip';
import { api } from '@/lib/apiClient';
import { formatLogTimestamp } from '@/features/workflow/lib/timeFormat';
import { useControlEvents } from '../context/ControlEventsContext';
import {
  DEFAULT_GROUPS,
  GROUPS,
  MAX_EVENTS,
  SEVERITIES,
  describe,
  groupOf,
  matches,
  typesOf,
  type ActivityEvent,
  type GroupId,
} from '../lib/activity';

/** Rows drawn; the rest of the buffer is still searched and counted. */
const VISIBLE_ROWS = 200;

const SEVERITY_CLASS: Record<string, string> = {
  INFO: 'text-on-surface-variant',
  WARN: 'text-attention',
  ERROR: 'text-error',
};

const TYPE_LABEL: Record<string, string> = Object.fromEntries(
  GROUPS.flatMap((g) => g.types.map((t) => [t, g.label]))
);

/** The run view of the event's run when its session is known, else a search for the run. */
function runLink(e: ActivityEvent): string {
  const { sessionId, workflowId, executionId } = e.event;
  return sessionId && workflowId
    ? `${api`/sessions/${sessionId}/workflow/${workflowId}`}?exec=${encodeURIComponent(executionId)}`
    : `/runs?text=${encodeURIComponent(executionId)}`;
}

function Toggle({
  label,
  values,
  chosen,
  onChange,
}: {
  label: string;
  values: readonly string[];
  chosen: string[];
  onChange: (next: string[]) => void;
}) {
  return (
    <fieldset className="flex flex-wrap items-center gap-1">
      <legend className="sr-only">{label}</legend>
      {values.map((value) => {
        const on = chosen.includes(value);
        return (
          <button
            key={value}
            type="button"
            aria-pressed={on}
            onClick={() =>
              onChange(
                on ? chosen.filter((c) => c !== value) : [...chosen, value]
              )
            }
            className={`text-label-sm rounded-lg border px-2 py-1 ${
              on
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border text-on-surface-variant'
            }`}
          >
            {value}
          </button>
        );
      })}
    </fieldset>
  );
}

/** The group chips, each with how many buffered events it holds. */
function GroupChips({
  chosen,
  counts,
  onChange,
}: {
  chosen: readonly GroupId[];
  counts: Record<string, number>;
  onChange: (next: GroupId[]) => void;
}) {
  return (
    <fieldset className="flex flex-wrap items-center gap-1">
      <legend className="sr-only">Event kinds</legend>
      {GROUPS.map((g) => {
        const on = chosen.includes(g.id);
        return (
          <button
            key={g.id}
            type="button"
            aria-pressed={on}
            onClick={() =>
              onChange(
                on ? chosen.filter((c) => c !== g.id) : [...chosen, g.id]
              )
            }
            className={`text-label-sm rounded-lg border px-2 py-1 ${
              on
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border text-on-surface-variant'
            }`}
          >
            {g.label}
            <span className="num ml-1 opacity-70">{counts[g.id]}</span>
          </button>
        );
      })}
    </fieldset>
  );
}

/** The live event feed: filters in the URL, pause and resume, a bounded buffer. */
export function ActivityPanel() {
  const { connected, events, dropped } = useControlEvents();
  const [params, setParams] = useSearchParams();
  const [frozenAt, setFrozenAt] = useState<number | null>(null);
  // no group chosen means the default set; ponytail: switching every group off shows the default
  const chosen = params.has('group')
    ? (params.getAll('group') as GroupId[])
    : [...DEFAULT_GROUPS];
  const severities = params.getAll('severity');
  // an older link names the run in `execution`
  const text = params.get('q') ?? params.get('execution') ?? '';
  const update = (change: (next: URLSearchParams) => void) => {
    const updated = new URLSearchParams(params);
    change(updated);
    setParams(updated, { replace: true });
  };

  const paused = frozenAt !== null;
  const shown = paused ? events.filter((e) => e.seq < frozenAt) : events;
  const waiting = events.length - shown.length;
  const narrowed = shown.filter((e) =>
    matches(e, { types: [], severities, text })
  );
  const counts: Record<string, number> = Object.fromEntries(
    GROUPS.map((g) => [
      g.id,
      narrowed.filter((e) => groupOf(e.type) === g.id).length,
    ])
  );
  const types = typesOf(chosen);
  const rows = narrowed.filter((e) => types.includes(e.type)).reverse();

  return (
    <section aria-label="Activity" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <GroupChips
          chosen={chosen}
          counts={counts}
          onChange={(next) =>
            update((u) => {
              u.delete('group');
              next.forEach((g) => u.append('group', g));
            })
          }
        />
        <Toggle
          label="Severities"
          values={SEVERITIES}
          chosen={severities}
          onChange={(next) =>
            update((u) => {
              u.delete('severity');
              next.forEach((s) => u.append('severity', s));
            })
          }
        />
        <Input
          aria-label="Run, workflow or node"
          placeholder="Run, workflow or node…"
          value={text}
          onChange={(event) =>
            update((u) => {
              u.delete('execution');
              const value = event.target.value.trim();
              if (value) u.set('q', value);
              else u.delete('q');
            })
          }
          className="w-56"
        />
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            setFrozenAt(
              paused ? null : (events[events.length - 1]?.seq ?? -1) + 1
            )
          }
        >
          {paused ? 'Resume' : 'Pause'}
        </Button>
        <span className="text-body-sm text-on-surface-variant">
          {connected ? 'Live' : 'Reconnecting…'}
          {paused && ` · ${waiting} new while paused`}
          {dropped > 0 &&
            ` · ${dropped} oldest dropped (keeping ${MAX_EVENTS})`}
        </span>
      </div>
      {rows.length === 0 ? (
        <StateMessage title="No events yet." icon="monitoring" compact>
          {chosen.includes('logs')
            ? 'Events appear here as workflows run.'
            : 'Events appear here as workflows run. Log lines are hidden: turn on Logs to see them.'}
        </StateMessage>
      ) : (
        <div
          className="border-border bg-card max-h-[calc(100dvh-20rem)] min-h-64 overflow-auto rounded-xl border"
          data-testid="activity-list"
        >
          <table className="text-body-sm w-full">
            <thead className="bg-card sticky top-0">
              <tr className="text-overline text-left">
                <th scope="col" className="px-3 py-1.5">
                  Time
                </th>
                <th scope="col">Level</th>
                <th scope="col">Kind</th>
                <th scope="col">Workflow</th>
                <th scope="col">Node</th>
                <th scope="col">What</th>
                <th scope="col">Run</th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, VISIBLE_ROWS).map((e) => (
                <tr key={e.seq} className="border-border border-t align-top">
                  <td className="text-code-sm text-on-surface-variant px-3 py-1 whitespace-nowrap">
                    {formatLogTimestamp(e.event.timestamp)}
                  </td>
                  <td className={SEVERITY_CLASS[e.severity]}>{e.severity}</td>
                  <td>
                    <Tip label={e.type}>
                      <span>{TYPE_LABEL[e.type]}</span>
                    </Tip>
                  </td>
                  <td className="text-code-sm">{e.event.workflowId ?? '–'}</td>
                  <td className="text-code-sm">{e.event.nodeId ?? '–'}</td>
                  <td className="text-on-surface min-w-0 wrap-break-word">
                    {describe(e)}
                  </td>
                  <td>
                    {e.event.executionId && (
                      <Tip label="Open this run">
                        <Link className="text-primary" to={runLink(e)}>
                          {e.event.executionId.slice(0, 8)}
                        </Link>
                      </Tip>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
