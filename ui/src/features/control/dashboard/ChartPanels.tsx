// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, useState } from 'react';
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
import {
  useAnnotations,
  useDashboardParams,
  useHeatmap,
  useMetricSeries,
} from '@/features/metrics/hooks';
import { PanelState } from './PanelState';
import { DayHeat } from './RunPanels';
import { useNow } from './useNow';
import {
  OPERATIONS,
  RUN_LINES,
  agoText,
  formatMs,
  lastRunAt,
  opOf,
} from './format';

// Recharts is its own chunk, loaded only when this panel is on screen.
const RunsChart = lazy(() => import('./RunsChart'));
const OverheadChart = lazy(() => import('./OverheadChart'));
const ApiChart = lazy(() => import('./ApiChart'));

const loading = <StateMessage compact tone="loading" title="Loading chart" />;

interface PanelProps {
  scope: Scope;
  range: Range;
  live: boolean;
}

const RUNS_INFO =
  'Workflow runs that finished per minute, stacked by how they ended. It counts runs, not API calls. Markers on the axis are control commands such as pause or stop.';

const API_INFO =
  "REST calls to this server per minute, stacked by what they do: read (GET), run (start or restart a run, inbound webhooks), change (create, update, delete, commands). The dashboard's own polling and event streams are not counted, nor are MCP calls. The time under the chart is the mean per call. Always the whole server.";

type View = 'runs' | 'api';

const ENGINE_INFO =
  "Time the engine itself adds: from a node taking its turn to its plugin being called (start), and between nodes (hand-off). It leaves out the plugins' own time. p50 is the typical value, p95 the slow tail.";

const RUN_INFO =
  'How long whole runs take from start to finish, per minute: p50 is the typical run, p95 the slow tail and p99 the slowest one in a hundred. This is the plugins and the engine together; see the engine view for the engine alone.';

/** Two or more ways to look at one panel, one pressed at a time. */
function ViewToggle<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: readonly (readonly [T, string])[];
  onChange: (next: T) => void;
}) {
  return (
    <div role="group" aria-label={label} className="flex gap-1">
      {options.map(([id, text]) => (
        <button
          key={id}
          type="button"
          aria-pressed={value === id}
          onClick={() => onChange(id)}
          className={`text-label-md rounded-md px-2 py-0.5 ${value === id ? 'bg-accent text-on-surface' : 'text-on-surface-variant'}`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

/**
 * What the server is doing: runs that ended, stacked by status (with the control commands marked on
 * the time axis), or the REST calls it served by kind. The toggle keeps the two from being mixed up.
 */
export function ThroughputPanel({ scope, range, live }: PanelProps) {
  const [view, setView] = useState<View>('runs');
  return (
    <Panel
      title="Throughput"
      info={view === 'runs' ? RUNS_INFO : API_INFO}
      action={
        <ViewToggle
          label="Throughput of"
          value={view}
          onChange={setView}
          options={[
            ['runs', 'Runs'],
            ['api', 'API calls'],
          ]}
        />
      }
    >
      {view === 'runs' ? (
        <RunsView scope={scope} range={range} live={live} />
      ) : (
        <ApiView range={range} />
      )}
    </Panel>
  );
}

function RunsView({ scope, range, live }: PanelProps) {
  const series = useMetricSeries('runs.', range, scope);
  const day = useHeatmap('24h', scope, live).data;
  const { setRange } = useDashboardParams();
  const marks = useAnnotations(range, scope, live);
  const navigate = useNavigate();
  const now = useNow();
  const open = (mark: ControlAnnotation | undefined) => {
    if (mark) void navigate(annotationTarget(mark));
  };
  const empty = Object.keys(series.data ?? {}).length === 0;
  const lastRun = lastRunAt(day ?? []);
  return (
    <>
      <PanelState
        isPending={series.isPending}
        error={series.error}
        empty={empty}
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
      {empty && !series.isPending && lastRun !== undefined && (
        <p className="text-body-sm text-on-surface-variant">
          The last run finished {agoText(now - lastRun)}.{' '}
          {range !== '24h' && (
            <button
              type="button"
              onClick={() => setRange('24h')}
              className="text-primary underline"
            >
              Show 24 h
            </button>
          )}
        </p>
      )}
      <DayHeat scope={scope} live={live} />
    </>
  );
}

function ApiView({ range }: { range: Range }) {
  const series = useMetricSeries('yukta.api.requests', range);
  const means = OPERATIONS.flatMap((op) => {
    const key = Object.keys(series.data ?? {}).find(
      (k) => opOf(k) === op && k.endsWith('|mean')
    );
    const points = key ? (series.data?.[key] as { v: number }[]) : [];
    const last = points[points.length - 1] as { v: number } | undefined;
    return last ? [`${op} ${formatMs(last.v)}`] : [];
  });
  return (
    <PanelState
      isPending={series.isPending}
      error={series.error}
      empty={Object.keys(series.data ?? {}).length === 0}
      emptyText="No API calls in this range"
      onRetry={() => void series.refetch()}
    >
      <Suspense fallback={loading}>
        <ApiChart series={series.data ?? {}} />
      </Suspense>
      {means.length > 0 && (
        <p className="text-label-sm text-on-surface-variant">
          Mean time per call: {means.join(' · ')}
        </p>
      )}
    </PanelState>
  );
}

/**
 * What the engine itself added: the time from a message taking its node's turn to the plugin being
 * called (hand-off), and from a run getting its slot to its first plugin call (start). Plugin
 * durations are not here: they mean nothing without the node, see the slowest-nodes panel.
 */
export function OverheadPanel({ scope, range }: PanelProps) {
  const [view, setView] = useState<'engine' | 'run'>('engine');
  const engine = useMetricSeries('overhead.', range, scope);
  const runTime = useMetricSeries('duration.', range, scope);
  const shown = view === 'engine' ? engine : runTime;
  const series = shown.data ?? {};
  return (
    <Panel
      title={view === 'engine' ? 'Engine overhead' : 'Run time'}
      info={view === 'engine' ? ENGINE_INFO : RUN_INFO}
      action={
        <ViewToggle
          label="Timings of"
          value={view}
          onChange={setView}
          options={[
            ['engine', 'Engine'],
            ['run', 'Run time'],
          ]}
        />
      }
    >
      <PanelState
        isPending={shown.isPending}
        error={shown.error}
        empty={Object.keys(series).length === 0}
        emptyText={
          view === 'engine'
            ? 'No engine timings in this range'
            : 'No runs finished in this range'
        }
        inline
        onRetry={() => void shown.refetch()}
      >
        <Suspense fallback={loading}>
          {view === 'engine' ? (
            <OverheadChart series={series} />
          ) : (
            <OverheadChart series={series} lines={RUN_LINES} label="Run time" />
          )}
        </Suspense>
      </PanelState>
    </Panel>
  );
}
