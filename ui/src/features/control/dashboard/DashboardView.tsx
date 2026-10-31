// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { ScopeBar } from '@/features/metrics/ScopeBar';
import { useDashboardParams, useMetricStream } from '@/features/metrics/hooks';
import {
  AiUsagePanel,
  BusPanel,
  VitalsPanel,
  WorkerSlotsPanel,
} from './HealthPanels';
import { CapacityPanel } from './CapacityPanel';
import { LatencyPanel, ThroughputPanel } from './ChartPanels';
import { CommandStrip } from './CommandStrip';
import { HeatPanel, LeaderboardPanel, LiveRunsPanel } from './RunPanels';

/**
 * The Mission Control dashboard: the scope, range and live controls (kept in the URL) over the
 * command strip and the panels. The metric stream keeps the cached numbers current while live.
 */
export function DashboardView() {
  const { scope, range, live } = useDashboardParams();
  useMetricStream(live);
  const sessions = useSessionSummaries().data ?? [];
  const scopes = [
    { value: 'global', label: 'Global' },
    ...sessions.map((s) => ({
      value: `session:${s.sessionId}`,
      label: `Session ${s.name || s.sessionId}`,
    })),
  ];
  // a scope from the URL that is not a known session (a workflow, say) still shows as chosen
  if (!scopes.some((s) => s.value === scope)) {
    scopes.push({ value: scope, label: scope });
  }
  return (
    <div className="flex flex-col gap-4" data-testid="mission-control">
      <ScopeBar scopes={scopes} />
      <CommandStrip scope={scope} live={live} />
      <div className="grid gap-4 lg:grid-cols-3">
        <CapacityPanel live={live} />
        <div className="lg:col-span-2">
          <ThroughputPanel scope={scope} range={range} live={live} />
        </div>
        <div className="lg:col-span-2">
          <LatencyPanel scope={scope} range={range} live={live} />
        </div>
        <LiveRunsPanel live={live} />
        <div className="lg:col-span-3">
          <HeatPanel scope={scope} live={live} />
        </div>
        <LeaderboardPanel scope={scope} range={range} live={live} />
        <BusPanel live={live} />
        <WorkerSlotsPanel />
        <VitalsPanel />
        <AiUsagePanel scope={scope} />
      </div>
    </div>
  );
}
