// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { StatusPill } from './StatusPill';
import { BAR_COLOR, buildTimeline } from '../lib/timeline';
import { formatClockTime, formatDuration } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import type { TaskProgress } from '../types/workflow';

/** How often a running node's bar grows between status updates. */
const TICK_MS = 1000;

export type RunView = 'graph' | 'timeline';

/** Graph or timeline: the two ways to look at the run. */
export function ViewSwitch({
  view,
  onChange,
  className,
}: {
  view: RunView;
  onChange: (view: RunView) => void;
  className?: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Run view"
      className={cn(
        'border-border bg-surface shadow-card flex gap-0.5 rounded-lg border p-0.5',
        className
      )}
    >
      {(
        [
          ['graph', 'Graph'],
          ['timeline', 'Timeline'],
        ] as const
      ).map(([value, label]) => (
        <button
          key={value}
          type="button"
          role="radio"
          aria-checked={view === value}
          onClick={() => onChange(value)}
          className={cn(
            'text-label-sm rounded-md px-2.5 py-1',
            view === value
              ? 'bg-muted text-on-surface'
              : 'text-on-surface-variant hover:text-on-surface'
          )}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * When each node ran and for how long, as bars on one time axis. It is a table, so a screen
 * reader gets node, status, start and duration per row; the bar column is only a picture.
 * Updates with the run: the status stream feeds `tasks`, and running bars grow each second.
 */
export function RunTimeline({
  tasks = [],
  switcher,
}: {
  /** Absent until the run's status has arrived. */
  tasks?: TaskProgress[];
  switcher: ReactNode;
}) {
  const running = tasks.some((task) => task.startTime && !task.endTime);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const timer = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(timer);
  }, [running]);
  const { rows } = useMemo(() => buildTimeline(tasks, now), [tasks, now]);

  return (
    <div
      className="bg-background absolute inset-0 overflow-auto p-3"
      data-testid="run-timeline"
    >
      <div className="mb-3">{switcher}</div>
      {rows.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">
          No node has started yet.
        </p>
      ) : (
        <table className="w-full table-fixed border-collapse">
          <caption className="sr-only">When each node ran</caption>
          <thead>
            <tr className="text-overline text-on-surface-variant text-left">
              <th scope="col" className="w-48 pb-1 font-normal">
                Node
              </th>
              <th scope="col" className="w-28 pb-1 font-normal">
                Status
              </th>
              <th scope="col" className="w-20 pb-1 font-normal">
                Start
              </th>
              <th scope="col" className="w-20 pb-1 font-normal">
                Duration
              </th>
              <th scope="col" className="pb-1 font-normal">
                Timeline
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.nodeId} className="border-border border-t">
                <th
                  scope="row"
                  className="text-body-sm text-on-surface truncate py-1.5 pr-2 text-left font-normal"
                  title={`${row.nodeId} (${row.module})`}
                >
                  {row.nodeId}
                </th>
                <td className="py-1.5 pr-2">
                  <StatusPill status={row.status} scope="node" />
                </td>
                <td className="num text-body-sm text-on-surface-variant py-1.5 pr-2">
                  {formatClockTime(new Date(row.startMs).toISOString())}
                </td>
                <td className="num text-body-sm text-on-surface-variant py-1.5 pr-2">
                  {formatDuration(
                    new Date(row.startMs).toISOString(),
                    new Date(row.endMs).toISOString()
                  )}
                </td>
                <td className="py-1.5">
                  <div
                    className="bg-muted relative h-3 rounded-full"
                    aria-hidden="true"
                  >
                    <div
                      data-testid={`timeline-bar-${row.nodeId}`}
                      className={cn(
                        'absolute inset-y-0 rounded-full',
                        BAR_COLOR[row.uiStatus]
                      )}
                      style={{ left: `${row.left}%`, width: `${row.width}%` }}
                    />
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
