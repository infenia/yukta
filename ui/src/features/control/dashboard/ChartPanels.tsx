// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense } from 'react';
import { useNavigate } from 'react-router';
import { Panel } from '@/components/dashboard/Tile';
import { ChartAnnotations } from '@/components/dashboard/Strips';
import { StateMessage } from '@/components/common/StateMessage';
import { api } from '@/lib/apiClient';
import {
  RANGE_MS,
  type ControlAnnotation,
  type Range,
  type Scope,
} from '@/features/metrics/api';
import { useAnnotations, useMetricSeries } from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { useNow } from './useNow';

// Recharts is its own chunk, loaded only when this panel is on screen.
const RunsChart = lazy(() => import('./RunsChart'));
const LatencyChart = lazy(() => import('./LatencyChart'));

const loading = <StateMessage compact tone="loading" title="Loading chart" />;

interface PanelProps {
  scope: Scope;
  range: Range;
  live: boolean;
}

/** Runs that ended, stacked by status, with the control commands marked on the time axis. */
export function ThroughputPanel({ scope, range, live }: PanelProps) {
  const series = useMetricSeries('runs.', range, scope);
  const marks = useAnnotations(range, scope, live);
  const navigate = useNavigate();
  const now = useNow();
  const open = (mark: ControlAnnotation | undefined) => {
    if (mark?.sessionId && mark.workflowId) {
      void navigate(
        api`/sessions/${mark.sessionId}/workflow/${mark.workflowId}`
      );
    }
  };
  return (
    <Panel title="Throughput">
      <PanelState
        isPending={series.isPending}
        error={series.error}
        empty={Object.keys(series.data ?? {}).length === 0}
        emptyText="No runs finished in this range"
        onRetry={() => void series.refetch()}
      >
        <div className="relative">
          <Suspense fallback={loading}>
            <RunsChart series={series.data ?? {}} />
          </Suspense>
          <div className="relative h-5" data-testid="annotations">
            <ChartAnnotations
              from={now - RANGE_MS[range]}
              to={now}
              items={(marks.data ?? []).map((m) => ({
                at: m.time,
                label: `${m.command}${m.actor ? ` by ${m.actor}` : ''}${m.applied ? '' : ' (refused)'}`,
                kind: m.applied ? 'attention' : 'danger',
              }))}
              onSelect={(item) =>
                open((marks.data ?? []).find((m) => m.time === item.at))
              }
            />
          </div>
        </div>
      </PanelState>
    </Panel>
  );
}

/** Run duration percentiles and the time starts wait for a slot. */
export function LatencyPanel({ scope, range }: PanelProps) {
  const duration = useMetricSeries('duration.', range, scope);
  const queue = useMetricSeries('queue.', range, scope);
  const series = { ...queue.data, ...duration.data };
  return (
    <Panel title="Latency">
      <PanelState
        isPending={duration.isPending || queue.isPending}
        error={duration.error ?? queue.error}
        empty={Object.keys(series).length === 0}
        emptyText="No timings in this range"
        onRetry={() => {
          void duration.refetch();
          void queue.refetch();
        }}
      >
        <Suspense fallback={loading}>
          <LatencyChart series={series} />
        </Suspense>
      </PanelState>
    </Panel>
  );
}
