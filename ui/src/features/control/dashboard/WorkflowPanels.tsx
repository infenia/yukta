// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Panel } from '@/components/dashboard/Tile';
import { api } from '@/lib/apiClient';
import type { Range, Scope, WorkflowRow } from '@/features/metrics/api';
import { useNodes, useWorkflows } from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { WorkflowDrawer } from './WorkflowDrawer';
import { formatMs } from './format';

/** Workflows listed before "Show all". */
const SHOWN = 8;

/** Nodes listed as failure hotspots. */
const HOTSPOTS = 5;

/**
 * How long each workflow takes: runs, failures and the typical (p50) and slow (p95) run time, with
 * a bar of the p95 against the slowest workflow. A row opens the workflow's drawer: its run time
 * over the range and node by node. Always the whole server.
 */
export function WorkflowsPanel({
  range,
  live,
}: {
  range: Range;
  live: boolean;
}) {
  const workflows = useWorkflows(range, live);
  const [all, setAll] = useState(false);
  const [open, setOpen] = useState<WorkflowRow | null>(null);
  const rows = workflows.data ?? [];
  const shown = all ? rows : rows.slice(0, SHOWN);
  const slowest = Math.max(1, ...rows.map((w) => w.p95Ms));
  return (
    <Panel
      title="Workflow durations"
      info="How long a run of each workflow takes from start to finish, in the chosen range: p50 is the typical run, p95 the slow tail (19 in 20 runs were faster). Click a workflow to see its run time over time and node by node. Always the whole server."
      action={
        rows.length > SHOWN && (
          <button
            type="button"
            onClick={() => setAll(!all)}
            className="text-label-md text-primary"
          >
            {all ? 'Show fewer' : `Show all ${rows.length}`}
          </button>
        )
      }
    >
      <PanelState
        isPending={workflows.isPending}
        error={workflows.error}
        empty={rows.length === 0}
        inline
        emptyText="No runs finished in this range"
        onRetry={() => void workflows.refetch()}
      >
        <div className="overflow-x-auto">
          <table className="text-body-sm w-full">
            <thead>
              <tr className="text-overline text-left">
                <th scope="col">Workflow</th>
                <th scope="col" className="text-right">
                  Runs
                </th>
                <th scope="col" className="text-right">
                  Failed
                </th>
                <th scope="col" className="text-right">
                  p50
                </th>
                <th scope="col" className="w-1/4 text-right">
                  p95
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((w) => (
                <tr key={w.workflowId} className="border-border border-t">
                  <th scope="row" className="text-left font-normal">
                    <button
                      type="button"
                      onClick={() => setOpen(w)}
                      className="text-primary max-w-48 truncate underline"
                    >
                      {w.workflowId}
                    </button>
                  </th>
                  <td className="num text-right">{w.runs}</td>
                  <td
                    className={`num text-right ${w.failures > 0 ? 'text-destructive' : 'text-on-surface-variant'}`}
                  >
                    {w.failures > 0 ? w.failures : '–'}
                  </td>
                  <td className="num text-right">
                    {w.p50Ms > 0 ? formatMs(w.p50Ms) : '–'}
                  </td>
                  <td className="num text-right">
                    <div className="flex items-center justify-end gap-2">
                      <span
                        aria-hidden="true"
                        className="bg-muted h-1.5 w-16 overflow-hidden rounded-full"
                      >
                        <span
                          className="bg-primary block h-full rounded-full"
                          style={{ width: `${(w.p95Ms / slowest) * 100}%` }}
                        />
                      </span>
                      {w.p95Ms > 0 ? formatMs(w.p95Ms) : '–'}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </PanelState>
      {open && (
        <WorkflowDrawer
          workflow={open}
          range={range}
          onClose={() => setOpen(null)}
        />
      )}
    </Panel>
  );
}

/**
 * The nodes that failed most in the range, with how often. Shown only when some node failed: a
 * healthy server has nothing to show here, and no failure history older than the range is kept.
 */
export function HotspotsPanel({
  scope,
  range,
  live,
}: {
  scope: Scope;
  range: Range;
  live: boolean;
}) {
  const nodes = useNodes(range, scope, live);
  const failing = (nodes.data ?? [])
    .filter((n) => n.failures > 0)
    .sort((a, b) => b.failures - a.failures)
    .slice(0, HOTSPOTS);
  if (failing.length === 0) {
    return null;
  }
  const worst = failing[0].failures;
  return (
    <Panel
      title="Failure hotspots"
      info="The nodes with the most failed runs in the chosen range, and what share of their runs failed. Each links to its latest run. Only the last 24 hours are kept, and a restart clears them."
    >
      <ul className="flex flex-col gap-2">
        {failing.map((n) => (
          <li
            key={`${n.sessionId}/${n.workflowId}/${n.nodeId}`}
            className="flex flex-col gap-1"
          >
            <div className="text-body-sm flex items-baseline justify-between gap-2">
              <span className="min-w-0 truncate">
                <Link
                  className="font-mono underline"
                  to={`${api`/sessions/${n.sessionId}/workflow/${n.workflowId}`}?exec=${encodeURIComponent(n.lastExecutionId)}&node=${encodeURIComponent(n.nodeId)}`}
                >
                  {n.nodeId}
                </Link>{' '}
                <span className="text-on-surface-variant">{n.workflowId}</span>
              </span>
              <span className="num text-destructive shrink-0">
                {n.failures} of {n.runs} (
                {Math.round((n.failures / n.runs) * 100)}%)
              </span>
            </div>
            <span
              aria-hidden="true"
              className="bg-muted h-1.5 overflow-hidden rounded-full"
            >
              <span
                className="bg-destructive block h-full rounded-full"
                style={{ width: `${(n.failures / worst) * 100}%` }}
              />
            </span>
          </li>
        ))}
      </ul>
    </Panel>
  );
}
