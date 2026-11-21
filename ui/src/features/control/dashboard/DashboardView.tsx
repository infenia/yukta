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
import { SupervisorsSummary } from '@/features/supervisors/components/SupervisorsSummary';

/**
 * The Mission Control dashboard: the scope, range and live controls (kept in the URL) over the
 * command strip and the panels. The metric stream keeps the cached numbers current while live.
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
      {/* two columns that each stack their own cards, so no card is stretched to its neighbour's
          height and no row leaves a hole */}
      <div className="grid items-start gap-3 lg:grid-cols-3">
        <div className="flex min-w-0 flex-col gap-3 lg:col-span-2">
          <ThroughputPanel scope={scope} range={range} live={live} />
          <LiveRunsPanel scope={scope} live={live} />
          <NodesPanel scope={scope} range={range} live={live} />
          <OverheadPanel scope={scope} range={range} live={live} />
        </div>
        <div className="flex min-w-0 flex-col gap-3">
          <CapacityPanel live={live} />
          <SupervisorsSummary />
          <LeaderboardPanel scope={scope} range={range} live={live} />
          <SystemPanel scope={scope} live={live} />
        </div>
      </div>
    </div>
  );
}
