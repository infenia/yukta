// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Panel } from '@/components/dashboard/Tile';
import { HeatStrip } from '@/components/dashboard/Strips';
import { MeterBar } from '@/components/dashboard/Gauges';
import { api } from '@/lib/apiClient';
import { type Range, type Scope } from '@/features/metrics/api';
import {
  useHeatmap,
  useLiveRuns,
  useModules,
  useNodes,
} from '@/features/metrics/hooks';
import { inScope } from './scopes';
import { PanelState } from './PanelState';
import { useNow } from './useNow';
import { formatMs } from './format';

const MINUTE = 60_000;

/** Running nodes named on a live run before "+n". */
const NODES_NAMED = 3;

/** Five-minute cells over the day: 288 of them in two rows is enough to see when work happened. */
const BUCKET = 5 * MINUTE;
const DAY_BUCKETS = 288;

/**
 * The last 24 hours as a thin band under the throughput chart: brighter with more runs, red with
 * failures.
 */
export function DayHeat({ scope, live }: { scope: Scope; live: boolean }) {
  const heat = useHeatmap('24h', scope, live);
  const end = Math.floor(useNow() / BUCKET);
  if (heat.isPending) return null;
  if (heat.error) {
    return (
      <p className="text-body-sm text-on-surface-variant">
        Could not load the last 24 hours.
      </p>
    );
  }
  const byBucket = new Map<number, { runs: number; failed: number }>();
  for (const c of heat.data ?? []) {
    const key = Math.floor(c.time / BUCKET);
    const now = byBucket.get(key) ?? { runs: 0, failed: 0 };
    byBucket.set(key, {
      runs: now.runs + c.runs,
      failed: now.failed + c.failed,
    });
  }
  const total = [...byBucket.values()].reduce((n, b) => n + b.runs, 0);
  const busiest = Math.max(1, ...[...byBucket.values()].map((b) => b.runs));
  const cells = Array.from({ length: DAY_BUCKETS }, (_, i) => {
    const bucket = end - DAY_BUCKETS + 1 + i;
    const cell = byBucket.get(bucket);
    const at = new Date(bucket * BUCKET).toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
    return {
      label: `${at}: ${cell?.runs ?? 0} runs, ${cell?.failed ?? 0} failed`,
      volume: (cell?.runs ?? 0) / busiest,
      failureRatio: cell && cell.runs > 0 ? cell.failed / cell.runs : 0,
    };
  });
  return (
    <div className="flex flex-col gap-1">
      <div className="text-label-sm text-on-surface-variant flex justify-between">
        <span>Last 24 hours</span>
        <span className="num">{total} runs</span>
      </div>
      <HeatStrip
        cells={cells}
        columns={DAY_BUCKETS / 2}
        label="Runs per five minutes, 24 hours"
      />
    </div>
  );
}

/**
 * Plugin types ranked by executions, with how many failed. A mean duration per type is left out:
 * a type such as PROCESS_EXECUTOR runs anything, so its mean says nothing (see the slowest nodes).
 */
export function LeaderboardPanel({
  scope,
  range,
  live,
}: {
  scope: Scope;
  range: Range;
  live: boolean;
}) {
  const modules = useModules(range, scope, live);
  return (
    <Panel
      title="Plugins"
      info="Plugin types ranked by how many node executions they ran in the chosen range and how many of those failed."
    >
      <PanelState
        isPending={modules.isPending}
        error={modules.error}
        empty={(modules.data ?? []).length === 0}
        inline
        emptyText="No nodes finished in this range"
        onRetry={() => void modules.refetch()}
      >
        <table className="text-body-sm w-full">
          <thead>
            <tr className="text-overline text-left">
              <th scope="col">Plugin</th>
              <th scope="col" className="text-right">
                Runs
              </th>
              <th scope="col" className="text-right">
                Failed
              </th>
            </tr>
          </thead>
          <tbody>
            {(modules.data ?? []).map((m) => (
              <tr key={m.module} className="border-border border-t">
                <th scope="row" className="text-left font-mono font-normal">
                  {m.module}
                </th>
                <td className="num text-right">{m.executions}</td>
                <td
                  className={`num text-right ${m.failures > 0 ? 'text-destructive' : 'text-on-surface-variant'}`}
                >
                  {m.failures > 0 ? m.failures : '–'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </PanelState>
    </Panel>
  );
}

/** Rows of the slowest-nodes table before "Show all". */
const NODES_SHOWN = 8;

/**
 * The slowest nodes by 95th percentile duration, each named with its workflow and session: a
 * duration only means something for one node of one workflow.
 */
export function NodesPanel({
  scope,
  range,
  live,
}: {
  scope: Scope;
  range: Range;
  live: boolean;
}) {
  const nodes = useNodes(range, scope, live);
  const [all, setAll] = useState(false);
  const rows = nodes.data ?? [];
  const shown = all ? rows : rows.slice(0, NODES_SHOWN);
  return (
    <Panel
      title="Slowest nodes"
      info="Nodes ranked by their 95th-percentile run time in the chosen range: 19 in 20 runs of the node were faster. Each links to its latest run."
      action={
        rows.length > NODES_SHOWN && (
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
        isPending={nodes.isPending}
        error={nodes.error}
        empty={rows.length === 0}
        inline
        emptyText="No nodes finished in this range"
        onRetry={() => void nodes.refetch()}
      >
        <div className="overflow-x-auto">
          <table className="text-body-sm w-full">
            <thead>
              <tr className="text-overline text-left">
                <th scope="col">Node</th>
                <th scope="col">Workflow · session</th>
                <th scope="col" className="text-right">
                  Runs
                </th>
                <th scope="col" className="text-right">
                  Failed
                </th>
                <th scope="col" className="text-right">
                  p50
                </th>
                <th scope="col" className="text-right">
                  p95
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((n) => (
                <tr
                  key={`${n.sessionId}/${n.workflowId}/${n.nodeId}`}
                  className="border-border border-t"
                >
                  <th scope="row" className="text-left font-normal">
                    <Link
                      className="font-mono underline"
                      to={`${api`/sessions/${n.sessionId}/workflow/${n.workflowId}`}?exec=${encodeURIComponent(n.lastExecutionId)}&node=${encodeURIComponent(n.nodeId)}`}
                    >
                      {n.nodeId}
                    </Link>{' '}
                    <span className="text-on-surface-variant">{n.module}</span>
                  </th>
                  <td className="max-w-48 truncate">
                    {n.workflowId}{' '}
                    <span className="text-on-surface-variant">
                      · {n.sessionId}
                    </span>
                  </td>
                  <td className="num text-right">{n.runs}</td>
                  <td
                    className={`num text-right ${n.failures > 0 ? 'text-destructive' : 'text-on-surface-variant'}`}
                  >
                    {n.failures > 0 ? n.failures : '–'}
                  </td>
                  <td className="num text-right">
                    {n.p50Ms > 0 ? formatMs(n.p50Ms) : '–'}
                  </td>
                  <td className="num text-right">
                    {n.p95Ms > 0 ? formatMs(n.p95Ms) : '–'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </PanelState>
    </Panel>
  );
}

/** Runs in progress: nodes done, and the time so far against the workflow's usual p95. */
export function LiveRunsPanel({
  scope,
  live,
}: {
  scope: Scope;
  live: boolean;
}) {
  const runs = useLiveRuns(live);
  const shown = (runs.data ?? []).filter((run) => inScope(scope, run));
  return (
    <Panel
      title="Live runs"
      info="Runs executing right now, the nodes running in each and the time so far against the workflow's usual 95th percentile. To pause or stop a run, open it."
    >
      <PanelState
        isPending={runs.isPending}
        error={runs.error}
        empty={shown.length === 0}
        inline
        emptyText="No run is going"
        onRetry={() => void runs.refetch()}
      >
        <ul className="flex flex-col gap-1.5">
          {shown.map((run) => {
            const slow = run.p95Ms !== null && run.elapsedMs > run.p95Ms;
            const nodesNow = run.currentNodes.slice(0, NODES_NAMED);
            const more = run.currentNodes.length - nodesNow.length;
            return (
              <li
                key={run.executionId}
                data-slow={slow}
                className={`border-border flex flex-col gap-1 rounded-lg border px-2.5 py-2 ${slow ? 'ring-warning ring-2' : ''}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 truncate">
                    <Link
                      className="text-label-md underline"
                      to={`${api`/sessions/${run.sessionId}/workflow/${run.workflowId}`}?exec=${encodeURIComponent(run.executionId)}`}
                    >
                      {run.workflowId}
                    </Link>
                    <span className="text-body-sm text-on-surface-variant">
                      {' '}
                      · {run.sessionId}
                    </span>
                    {run.paused && (
                      <span className="text-label-sm text-warning">
                        {' '}
                        · paused
                      </span>
                    )}
                  </div>
                </div>
                <div className="text-body-sm text-on-surface-variant flex justify-between gap-2">
                  <span className="truncate">
                    {nodesNow.length > 0
                      ? `${nodesNow.join(', ')}${more > 0 ? ` +${more}` : ''}`
                      : 'between nodes'}
                  </span>
                  <span className="num shrink-0">
                    {formatMs(run.elapsedMs)}
                    {run.p95Ms !== null && ` of usual ${formatMs(run.p95Ms)}`}
                    {slow && ' (slow)'}
                  </span>
                </div>
                <MeterBar
                  label={`${run.workflowId}: ${run.nodesDone} of ${run.nodesSeen} nodes done`}
                  value={run.nodesDone}
                  max={Math.max(1, run.nodesSeen)}
                  tone={slow ? 'warning' : 'primary'}
                />
              </li>
            );
          })}
        </ul>
      </PanelState>
    </Panel>
  );
}
