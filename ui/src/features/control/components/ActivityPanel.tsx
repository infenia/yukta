// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { StateMessage } from '@/components/common/StateMessage';
import { formatLogTimestamp } from '@/features/workflow/lib/timeFormat';
import { useControlEvents } from '../context/ControlEventsContext';
import {
  EVENT_TYPES,
  MAX_EVENTS,
  SEVERITIES,
  describe,
  matches,
  type ActivityFilters,
} from '../lib/activity';

/** Rows drawn; the rest of the buffer is still searched and counted. */
const VISIBLE_ROWS = 200;

const SEVERITY_CLASS: Record<string, string> = {
  INFO: 'text-on-surface-variant',
  WARN: 'text-attention',
  ERROR: 'text-error',
};

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

/** The live event feed: filters in the URL, pause and resume, a bounded buffer. */
export function ActivityPanel() {
  const { connected, events, dropped } = useControlEvents();
  const [params, setParams] = useSearchParams();
  const [frozenAt, setFrozenAt] = useState<number | null>(null);
  const filters: ActivityFilters = {
    types: params.getAll('type'),
    severities: params.getAll('severity'),
    execution: params.get('execution') ?? '',
  };
  const setFilters = (next: ActivityFilters) => {
    const updated = new URLSearchParams(params);
    for (const key of ['type', 'severity', 'execution']) updated.delete(key);
    next.types.forEach((t) => updated.append('type', t));
    next.severities.forEach((s) => updated.append('severity', s));
    if (next.execution) updated.set('execution', next.execution);
    setParams(updated, { replace: true });
  };

  const paused = frozenAt !== null;
  const shown = paused ? events.filter((e) => e.seq < frozenAt) : events;
  const waiting = events.length - shown.length;
  const rows = shown.filter((e) => matches(e, filters)).reverse();

  return (
    <section aria-label="Activity" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <Toggle
          label="Event types"
          values={EVENT_TYPES}
          chosen={filters.types}
          onChange={(types) => setFilters({ ...filters, types })}
        />
        <Toggle
          label="Severities"
          values={SEVERITIES}
          chosen={filters.severities}
          onChange={(severities) => setFilters({ ...filters, severities })}
        />
        <Input
          aria-label="Execution id"
          placeholder="Execution id…"
          value={filters.execution}
          onChange={(event) =>
            setFilters({ ...filters, execution: event.target.value.trim() })
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
          Events appear here as workflows run.
        </StateMessage>
      ) : (
        <ul
          className="divide-border border-border bg-card max-h-128 divide-y overflow-y-auto rounded-xl border"
          data-testid="activity-list"
        >
          {rows.slice(0, VISIBLE_ROWS).map((e) => (
            <li
              key={e.seq}
              className="text-code-sm flex flex-wrap gap-x-3 px-3 py-1.5"
            >
              <span className="text-on-surface-variant">
                {formatLogTimestamp(e.event.timestamp)}
              </span>
              <span className={SEVERITY_CLASS[e.severity]}>{e.severity}</span>
              <span>{e.type}</span>
              {e.event.executionId && (
                <Link
                  className="text-primary"
                  to={`/runs?text=${encodeURIComponent(e.event.executionId)}`}
                  title="Find this run"
                >
                  {e.event.executionId.slice(0, 8)}
                </Link>
              )}
              <span className="text-on-surface min-w-0 wrap-break-word">
                {describe(e)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
