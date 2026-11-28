// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense } from 'react';
import { Link } from 'react-router';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';
import { api } from '@/lib/apiClient';
import type { Range, WorkflowRow } from '@/features/metrics/api';
import {
  useDashboardParams,
  useMetricSeries,
  useNodes,
} from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { formatMs } from './format';

const SeriesChart = lazy(() => import('@/features/metrics/SeriesChart'));

/**
 * One workflow's run time over the range and, under it, the same range node by node, so it is
 * clear where the time goes. It reads the minute aggregates the dashboard already keeps, so
 * nothing is stored for it. "Focus" narrows the whole dashboard to this workflow.
 */
export function WorkflowDrawer({
  workflow,
  range,
  onClose,
}: {
  workflow: WorkflowRow;
  range: Range;
  onClose: () => void;
}) {
  const scope = `workflow:${workflow.workflowId}`;
  const { setScope } = useDashboardParams();
  const series = useMetricSeries('duration.', range, scope);
  const nodes = useNodes(range, scope, false);
  const p95 = series.data?.['duration.p95'] ?? [];
  return (
    <Sheet open onOpenChange={() => onClose()}>
      <SheetContent className="sm:w-130">
        <SheetTitle className="truncate">{workflow.workflowId}</SheetTitle>
        <p className="text-body-sm text-on-surface-variant">
          How long runs of this workflow take, in the last {range}. The 95th
          percentile means 19 runs in 20 were faster.
        </p>
        <dl className="grid grid-cols-4 gap-2">
          {[
            ['Runs', String(workflow.runs)],
            ['Failed', String(workflow.failures)],
            ['p50', workflow.p50Ms > 0 ? formatMs(workflow.p50Ms) : '–'],
            ['p95', workflow.p95Ms > 0 ? formatMs(workflow.p95Ms) : '–'],
          ].map(([name, value]) => (
            <div key={name} className="min-w-0">
              <dt className="text-label-sm text-on-surface-variant">{name}</dt>
              <dd className="text-label-md num text-on-surface truncate">
                {value}
              </dd>
            </div>
          ))}
        </dl>
        <div className="h-48">
          {p95.length > 1 ? (
            <Suspense
              fallback={
                <StateMessage compact tone="loading" title="Loading chart" />
              }
            >
              <SeriesChart
                points={p95}
                label="Run time, p95"
                format={formatMs}
              />
            </Suspense>
          ) : (
            <StateMessage compact title="Not enough runs to chart yet" />
          )}
        </div>
        <h3 className="text-label-md text-on-surface">By node</h3>
        <PanelState
          isPending={nodes.isPending}
          error={nodes.error}
          empty={(nodes.data ?? []).length === 0}
          inline
          emptyText="No node timings in this range"
          onRetry={() => void nodes.refetch()}
        >
          <div className="overflow-x-auto">
            <table className="text-body-sm w-full">
              <thead>
                <tr className="text-overline text-left">
                  <th scope="col">Node</th>
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
                {(nodes.data ?? []).map((n) => (
                  <tr
                    key={`${n.sessionId}/${n.nodeId}`}
                    className="border-border border-t"
                  >
                    <th scope="row" className="text-left font-normal">
                      <Link
                        className="font-mono underline"
                        to={`${api`/sessions/${n.sessionId}/workflow/${n.workflowId}`}?exec=${encodeURIComponent(n.lastExecutionId)}&node=${encodeURIComponent(n.nodeId)}`}
                      >
                        {n.nodeId}
                      </Link>{' '}
                      <span className="text-on-surface-variant">
                        {n.module}
                      </span>
                    </th>
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
        <div className="flex justify-between gap-2">
          <Button
            variant="outline"
            onClick={() => {
              setScope(scope);
              onClose();
            }}
          >
            Focus the dashboard on this workflow
          </Button>
          <SheetClose render={<Button variant="ghost" />}>Close</SheetClose>
        </div>
      </SheetContent>
    </Sheet>
  );
}
