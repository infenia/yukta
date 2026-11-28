// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense } from 'react';
import { useNavigate } from 'react-router';
import { Panel } from '@/components/dashboard/Tile';
import { ChartAnnotations } from '@/components/dashboard/Strips';
import { StateMessage } from '@/components/common/StateMessage';
import { annotationLabel, annotationTarget } from './annotations';
import {
  RANGE_MS,
  type ControlAnnotation,
  type Range,
  type Scope,
} from '@/features/metrics/api';
import { useAnnotations, useMetricSeries } from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { DayHeat } from './RunPanels';
import { useNow } from './useNow';

// Recharts is its own chunk, loaded only when this panel is on screen.
const RunsChart = lazy(() => import('./RunsChart'));
const OverheadChart = lazy(() => import('./OverheadChart'));

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
    if (mark) void navigate(annotationTarget(mark));
  };
  return (
    <Panel
      title="Throughput"
      info="Workflow runs that finished per minute, stacked by how they ended. It counts runs, not API calls. Markers on the axis are control commands such as pause or stop."
    >
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
                label: annotationLabel(m),
                kind: m.applied ? 'attention' : 'danger',
              }))}
              onSelect={(item) =>
                open((marks.data ?? []).find((m) => m.time === item.at))
              }
            />
          </div>
        </div>
      </PanelState>
      <DayHeat scope={scope} live={live} />
    </Panel>
  );
}

/**
 * What the engine itself added: the time from a message taking its node's turn to the plugin being
 * called (hand-off), and from a run getting its slot to its first plugin call (start). Plugin
 * durations are not here: they mean nothing without the node, see the slowest-nodes panel.
 */
export function OverheadPanel({ scope, range }: PanelProps) {
  const overhead = useMetricSeries('overhead.', range, scope);
  return (
    <Panel
      title="Engine overhead"
      info="Time the engine itself adds: from a node taking its turn to its plugin being called (start), and between nodes (hand-off). It leaves out the plugins' own time. p50 is the typical value, p95 the slow tail."
    >
      <PanelState
        isPending={overhead.isPending}
        error={overhead.error}
        empty={Object.keys(overhead.data ?? {}).length === 0}
        emptyText="No engine timings in this range"
        inline
        onRetry={() => void overhead.refetch()}
      >
        <Suspense fallback={loading}>
          <OverheadChart series={overhead.data ?? {}} />
        </Suspense>
      </PanelState>
    </Panel>
  );
}
