// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { ArcGauge, MeterBar } from '@/components/dashboard/Gauges';
import { Panel } from '@/components/dashboard/Tile';
import { StatusMatrix, type MatrixState } from '@/components/dashboard/Strips';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import type { Scope } from '@/features/metrics/api';
import { getHeartbeats } from '../api/controlBus';
import { useWorkers } from '../api/workers';
import { PanelState } from './PanelState';
import { useNow } from './useNow';
import { formatMs, sumOf } from './format';

/** A heartbeat older than this is stale, and older than twice this is down. */
const STALE_MS = 30_000;

const stateOf = (ageMs: number): MatrixState =>
  ageMs <= STALE_MS ? 'ok' : ageMs <= 2 * STALE_MS ? 'stale' : 'down';

/** One cell per control-bus node, green while its heartbeat is fresh, amber then red as it ages. */
export function BusPanel({ live }: { live: boolean }) {
  const beats = useQuery({
    queryKey: ['control', 'heartbeats'],
    queryFn: getHeartbeats,
    refetchInterval: live ? 5000 : false,
  });
  const now = useNow(5000);
  return (
    <Panel title="Control bus">
      <PanelState
        isPending={beats.isPending}
        error={beats.error}
        empty={(beats.data ?? []).length === 0}
        emptyText="No node has sent a heartbeat"
        onRetry={() => void beats.refetch()}
      >
        <StatusMatrix
          items={(beats.data ?? []).map((b) => ({
            id: `${b.workflowId}/${b.nodeId}/${b.executionId}`,
            label: `${b.workflowId} / ${b.nodeId}`,
            state: stateOf(now - Date.parse(b.timestamp)),
          }))}
        />
      </PanelState>
    </Panel>
  );
}

/** Slots in use per connected worker. */
export function WorkerSlotsPanel() {
  const workers = useWorkers();
  return (
    <Panel title="Workers">
      <PanelState
        isPending={workers.isPending}
        error={workers.error}
        empty={(workers.data ?? []).length === 0}
        emptyText="No worker is connected"
        onRetry={() => void workers.refetch()}
      >
        <ul className="flex flex-col gap-2">
          {(workers.data ?? []).map((w) => (
            <li key={w.workerId}>
              <MeterBar
                label={`${w.workerId}${w.draining ? ' (draining)' : ''}: ${w.usedSlots} of ${w.capacity} slots`}
                value={w.usedSlots}
                max={Math.max(1, w.capacity)}
                tone={w.usedSlots >= w.capacity ? 'warning' : 'primary'}
              />
            </li>
          ))}
        </ul>
      </PanelState>
    </Panel>
  );
}

/** Heap, CPU and threads as gauges, with the worst garbage-collection pause and dropped events. */
export function VitalsPanel() {
  const snapshot = useMetricSnapshot();
  const values = snapshot.data;
  const heapUsed = sumOf(values, 'jvm.memory.used{area=heap');
  const heapMax = sumOf(values, 'jvm.memory.max{area=heap');
  const cpu = (values?.['process.cpu.usage'] ?? 0) * 100;
  const threads = values?.['jvm.threads.live'] ?? 0;
  return (
    <Panel title="System">
      <PanelState
        isPending={snapshot.isPending}
        error={snapshot.error}
        empty={!values || Object.keys(values).length === 0}
        emptyText="No samples yet"
        onRetry={() => void snapshot.refetch()}
      >
        <div className="grid grid-cols-3 gap-2">
          <ArcGauge
            label={`Heap ${Math.round(heapUsed / 1e6)} of ${Math.round(heapMax / 1e6)} MB`}
            value={heapUsed}
            max={Math.max(1, heapMax)}
          />
          <ArcGauge label={`CPU ${Math.round(cpu)}%`} value={cpu} max={100} />
          <ArcGauge
            label={`Threads ${Math.round(threads)}`}
            value={threads}
            max={Math.max(100, threads)}
          />
        </div>
        <dl className="mt-2 flex gap-4 text-body-sm text-on-surface-variant">
          <div>
            <dt>Longest GC pause</dt>
            <dd className="num text-on-surface">
              {formatMs(
                Math.max(
                  0,
                  ...Object.entries(values ?? {})
                    .filter(
                      ([k]) =>
                        k.startsWith('jvm.gc.pause') && k.endsWith('|max')
                    )
                    .map(([, v]) => v)
                )
              )}
            </dd>
          </div>
          <div>
            <dt>Dropped events / min</dt>
            <dd className="num text-on-surface">
              {(sumOf(values, 'yukta.tracker.dropped', '|rate') * 60).toFixed(
                1
              )}
            </dd>
          </div>
        </dl>
      </PanelState>
    </Panel>
  );
}

/** Model tokens, spend and tool-call traffic. */
export function AiUsagePanel({ scope }: { scope: Scope }) {
  const snapshot = useMetricSnapshot();
  const values = snapshot.data;
  const rows = [
    ['Tokens in / min', sumOf(values, 'llm.tokens', '|rate') * 60],
    ['Spend / hour', sumOf(values, 'llm.cost', '|rate') * 3600],
    ['MCP calls / min', sumOf(values, 'yukta.mcp.', '|rate') * 60],
  ] as const;
  return (
    <Panel title="AI usage">
      <PanelState
        isPending={snapshot.isPending}
        error={snapshot.error}
        empty={scope !== 'global' || rows.every(([, v]) => v === 0)}
        emptyText={
          scope === 'global'
            ? 'No model or tool calls yet'
            : 'AI usage is server-wide; choose the global scope'
        }
        onRetry={() => void snapshot.refetch()}
      >
        <dl className="grid grid-cols-3 gap-2 text-body-md">
          {rows.map(([label, value]) => (
            <div key={label}>
              <dt className="text-body-sm text-on-surface-variant">{label}</dt>
              <dd className="num text-on-surface">
                {value.toFixed(value < 10 ? 2 : 0)}
              </dd>
            </div>
          ))}
        </dl>
      </PanelState>
    </Panel>
  );
}
