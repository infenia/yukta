// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Panel } from '@/components/dashboard/Tile';
import { HeatStrip } from '@/components/dashboard/Strips';
import { MeterBar } from '@/components/dashboard/Gauges';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/common/InlineError';
import { api } from '@/lib/apiClient';
import {
  runAction,
  type RunAction,
} from '@/features/workflow/api/executionControl';
import type { Range, Scope } from '@/features/metrics/api';
import { useHeatmap, useLiveRuns, useModules } from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { useNow } from './useNow';
import { formatMs } from './format';

const MINUTE = 60_000;
const DAY_MINUTES = 24 * 60;

/** One cell per minute of the last 24 hours, brighter with more runs and red with failures. */
export function HeatPanel({ scope, live }: { scope: Scope; live: boolean }) {
  const heat = useHeatmap('24h', scope, live);
  const byMinute = new Map(
    (heat.data ?? []).map((c) => [Math.floor(c.time / MINUTE), c])
  );
  const end = Math.floor(useNow() / MINUTE);
  const busiest = Math.max(1, ...(heat.data ?? []).map((c) => c.runs));
  const cells = Array.from({ length: DAY_MINUTES }, (_, i) => {
    const minute = end - DAY_MINUTES + 1 + i;
    const cell = byMinute.get(minute);
    return {
      label: `${new Date(minute * MINUTE).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}: ${cell?.runs ?? 0} runs, ${cell?.failed ?? 0} failed`,
      volume: (cell?.runs ?? 0) / busiest,
      failureRatio: cell && cell.runs > 0 ? cell.failed / cell.runs : 0,
    };
  });
  return (
    <Panel title="Runs, last 24 hours">
      <PanelState
        isPending={heat.isPending}
        error={heat.error}
        empty={(heat.data ?? []).length === 0}
        emptyText="No runs in the last 24 hours"
        onRetry={() => void heat.refetch()}
      >
        <HeatStrip
          cells={cells}
          columns={96}
          label="Runs per minute, 24 hours"
        />
      </PanelState>
    </Panel>
  );
}

/** Plugin types ranked by executions, with their failure share and mean duration. */
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
    <Panel title="Plugins">
      <PanelState
        isPending={modules.isPending}
        error={modules.error}
        empty={(modules.data ?? []).length === 0}
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
              <th scope="col" className="text-right">
                Mean
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
                  className={`num text-right ${m.failures > 0 ? 'text-destructive' : ''}`}
                >
                  {Math.round((m.failures / m.executions) * 100)}%
                </td>
                <td className="num text-right">{formatMs(m.avgMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </PanelState>
    </Panel>
  );
}

/** Runs in progress: nodes done, and the time so far against the workflow's usual p95. */
export function LiveRunsPanel({ live }: { live: boolean }) {
  const runs = useLiveRuns(live);
  const client = useQueryClient();
  const act = useMutation({
    mutationFn: ({ id, action }: { id: string; action: RunAction }) =>
      runAction(id, action),
    onSettled: () =>
      client.invalidateQueries({ queryKey: ['metrics', 'live-runs'] }),
  });
  return (
    <Panel title="Live runs">
      <PanelState
        isPending={runs.isPending}
        error={runs.error}
        empty={(runs.data ?? []).length === 0}
        emptyText="No run is going"
        onRetry={() => void runs.refetch()}
      >
        <ul className="flex flex-col gap-2">
          {(runs.data ?? []).map((run) => {
            const slow = run.p95Ms !== null && run.elapsedMs > run.p95Ms;
            return (
              <li
                key={run.executionId}
                data-slow={slow}
                className={`border-border flex flex-col gap-1 rounded-lg border p-2 ${slow ? 'ring-warning ring-2' : ''}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <Link
                    className="text-label-md truncate underline"
                    to={api`/sessions/${run.sessionId}/workflow/${run.workflowId}`}
                  >
                    {run.workflowId}
                  </Link>
                  <span className="text-body-sm num text-on-surface-variant">
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
                <div className="flex gap-2">
                  {(['pause', 'stop'] as const).map((action) => (
                    <Button
                      key={action}
                      size="sm"
                      variant="outline"
                      disabled={act.isPending}
                      onClick={() =>
                        act.mutate({ id: run.executionId, action })
                      }
                      aria-label={`${action} ${run.workflowId} run ${run.executionId}`}
                    >
                      {action === 'pause' ? 'Pause' : 'Stop'}
                    </Button>
                  ))}
                </div>
              </li>
            );
          })}
        </ul>
        {act.error && <InlineError message={act.error.message} />}
      </PanelState>
    </Panel>
  );
}
