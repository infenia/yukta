// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Donut } from '@/components/dashboard/Gauges';
import { Panel } from '@/components/dashboard/Tile';
import type { Tone } from '@/components/dashboard/tones';
import type { Scope } from '@/features/metrics/api';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { PanelState } from './PanelState';

const TONES: Record<string, Tone> = {
  SUCCESS: 'success',
  FAILURE: 'danger',
  TIMED_OUT: 'warning',
};

/** `runs.finished` and `runs.failed` are totals of the statuses, not statuses. */
const TOTALS = new Set(['runs.finished', 'runs.failed']);

/**
 * How the runs of the last hour ended, as shares of a whole: succeeded, failed, timed out,
 * cancelled. It follows the scope, and reads the snapshot the tiles already hold.
 */
export function OutcomesPanel({ scope }: { scope: Scope }) {
  const snapshot = useMetricSnapshot(scope);
  const outcomes = Object.entries(snapshot.data ?? {})
    .filter(([key]) => key.startsWith('runs.') && !TOTALS.has(key))
    .map(([key, value]) => ({
      label: key.slice('runs.'.length),
      value,
      tone: TONES[key.slice('runs.'.length)] ?? ('neutral' as Tone),
    }))
    .sort((a, b) => b.value - a.value);
  const total = outcomes.reduce((n, o) => n + o.value, 0);
  return (
    <Panel
      title="Outcomes (1 h)"
      info="How the runs that finished in the last hour ended, as shares of all of them: succeeded, failed, timed out, cancelled or stopped."
    >
      <PanelState
        isPending={snapshot.isPending}
        error={snapshot.error}
        empty={total === 0}
        inline
        emptyText="No runs finished in the last hour"
        onRetry={() => void snapshot.refetch()}
      >
        <div className="flex items-center gap-4">
          <Donut segments={outcomes} />
          <ul className="text-body-sm min-w-0 flex-1">
            {outcomes.map((o) => (
              <li key={o.label} className="flex justify-between gap-2">
                <span className="truncate">{o.label}</span>
                <span className="num text-on-surface-variant">
                  {o.value} ({Math.round((o.value / total) * 100)}%)
                </span>
              </li>
            ))}
          </ul>
        </div>
      </PanelState>
    </Panel>
  );
}
