// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useSessionDetails } from '@/features/sessions/hooks/useSessionDetails';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { ScopeBar } from '@/features/metrics/ScopeBar';
import {
  useDashboardParams,
  useLiveRuns,
  useMetricStream,
} from '@/features/metrics/hooks';
import { scopeOptions, sessionOf } from './scopes';
import { SystemPanel } from './HealthPanels';
import { CapacityPanel } from './CapacityPanel';
import { OverheadPanel, ThroughputPanel } from './ChartPanels';
import { CommandStrip } from './CommandStrip';
import { KioskView } from './KioskView';
import { LeaderboardPanel, LiveRunsPanel, NodesPanel } from './RunPanels';
import { OutcomesPanel } from './OutcomesPanel';
import { HotspotsPanel, WorkflowsPanel } from './WorkflowPanels';
import { SupervisorsSummary } from '@/features/supervisors/components/SupervisorsSummary';

/**
 * The Dashboard: the scope, range and live controls (kept in the URL) over the tile strip and the
 * panels. It only reads. The metric stream keeps the cached numbers current while live.
 *
 * Cards that keep their size (charts, capacity, supervisors) come first; the ones that grow and
 * shrink with the data (live runs, plugins, slowest nodes, system and workers) sit at the bottom,
 * so a run starting or finishing moves nothing above them.
 */
export function DashboardView() {
  const { scope, range, live } = useDashboardParams();
  useMetricStream(live);
  const [kiosk, setKiosk] = useState(false);
  const sessions = useSessionSummaries().data ?? [];
  const chosenSession = useSessionDetails(sessionOf(scope)).data;
  const running = useLiveRuns(live).data ?? [];
  const scopes = scopeOptions({
    sessions,
    workflowIds: [
      ...(chosenSession?.workflowIds ?? []),
      ...sessions.map((s) => s.lastRun?.workflowId),
      ...running.map((r) => r.workflowId),
    ],
    current: scope,
  });
  return (
    <div className="flex flex-col gap-3" data-testid="mission-control">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <ScopeBar scopes={scopes} />
        <button
          type="button"
          onClick={() => setKiosk(true)}
          className="border-border text-label-md h-8 rounded-lg border px-2.5"
        >
          Kiosk
        </button>
      </div>
      {kiosk && <KioskView onExit={() => setKiosk(false)} />}
      <CommandStrip scope={scope} live={live} />
      <div className="grid items-start gap-3 lg:grid-cols-3">
        <div className="min-w-0 lg:col-span-2">
          <ThroughputPanel scope={scope} range={range} live={live} />
        </div>
        <CapacityPanel live={live} />
        <div className="min-w-0 lg:col-span-2">
          <OverheadPanel scope={scope} range={range} live={live} />
        </div>
        <SupervisorsSummary />
        <div className="min-w-0 lg:col-span-2">
          <WorkflowsPanel range={range} live={live} />
        </div>
        <div className="flex min-w-0 flex-col gap-3">
          <OutcomesPanel scope={scope} />
          <HotspotsPanel scope={scope} range={range} live={live} />
        </div>
        {/* from here the cards change height with the data */}
        <div className="flex min-w-0 flex-col gap-3 lg:col-span-2">
          <LiveRunsPanel scope={scope} live={live} />
          <NodesPanel scope={scope} range={range} live={live} />
        </div>
        <div className="flex min-w-0 flex-col gap-3">
          <LeaderboardPanel scope={scope} range={range} live={live} />
          <SystemPanel live={live} />
        </div>
      </div>
    </div>
  );
}
