// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { ArcGauge, MeterBar } from '@/components/dashboard/Gauges';
import { Panel } from '@/components/dashboard/Tile';
import { StatusMatrix, type MatrixState } from '@/components/dashboard/Strips';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { GLOBAL, type Scope } from '@/features/metrics/api';
import { getHeartbeats } from '../api/controlBus';
import { useWorkers } from '../api/workers';
import { PanelState } from './PanelState';
import { useNow } from './useNow';
import { aiUsage, formatMs, sumOf } from './format';

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
export function WorkerSlotsPanel({ live = true }: { live?: boolean }) {
  const workers = useWorkers(live);
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
            <li key={w.workerId} className="flex flex-col gap-1">
              <div className="text-body-sm flex justify-between gap-2">
                <span className="truncate font-mono">
                  {w.workerId}
                  {w.draining ? ' (draining)' : ''}
                </span>
                <span className="num text-on-surface-variant shrink-0">
                  {w.usedSlots} / {w.capacity}
                </span>
              </div>
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
          {[
            {
              name: 'Heap',
              caption: `${Math.round(heapUsed / 1e6)} of ${Math.round(heapMax / 1e6)} MB`,
              value: heapUsed,
              max: Math.max(1, heapMax),
              text: `${Math.round((heapUsed / Math.max(1, heapMax)) * 100)}%`,
            },
            {
              name: 'CPU',
              caption: 'process',
              value: cpu,
              max: 100,
              text: `${Math.round(cpu)}%`,
            },
            {
              name: 'Threads',
              caption: 'live',
              value: threads,
              max: Math.max(100, threads),
              text: `${Math.round(threads)}`,
            },
          ].map((g) => (
            <figure key={g.name} className="flex min-w-0 flex-col items-center">
              <ArcGauge
                label={`${g.name} ${g.text}, ${g.caption}`}
                value={g.value}
                max={g.max}
                valueText={g.text}
              />
              <figcaption className="text-body-sm text-on-surface-variant truncate text-center">
                {g.name} · {g.caption}
              </figcaption>
            </figure>
          ))}
        </div>
        <dl className="text-body-sm text-on-surface-variant mt-2 flex gap-4">
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

/** A rate with two decimals while small and none once it is large. */
const rate = (value: number) => value.toFixed(value < 10 ? 2 : 0);

/**
 * Model tokens (in and out) and spend per provider, and the MCP tool-call traffic with the failed
 * and the refused heavy calls. These meters carry no session or workflow tag, so the panel shows
 * the whole server and says so in a scoped view.
 */
export function AiUsagePanel({ scope }: { scope: Scope }) {
  const snapshot = useMetricSnapshot();
  const usage = aiUsage(snapshot.data);
  const tools = [
    ['MCP calls / min', usage.mcpCallsPerMinute],
    ['Failed calls / min', usage.mcpFailedPerMinute],
    ['Refused heavy calls / min', usage.heavyRefusedPerMinute],
  ] as const;
  const idle =
    usage.providers.length === 0 && tools.every(([, value]) => value === 0);
  return (
    <Panel
      title="AI usage"
      action={
        scope === GLOBAL ? undefined : (
          <span className="text-body-sm text-on-surface-variant">
            Whole server
          </span>
        )
      }
    >
      <PanelState
        isPending={snapshot.isPending}
        error={snapshot.error}
        empty={idle}
        emptyText="No model or tool calls yet"
        onRetry={() => void snapshot.refetch()}
      >
        <div className="flex flex-col gap-3">
          {usage.providers.length > 0 && (
            <div className="overflow-x-auto">
              <table className="text-body-sm w-full">
                <thead>
                  <tr className="text-overline text-left">
                    <th scope="col">Provider</th>
                    <th scope="col" className="text-right">
                      Tokens in / min
                    </th>
                    <th scope="col" className="text-right">
                      Tokens out / min
                    </th>
                    <th scope="col" className="text-right">
                      Spend / hour
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {usage.providers.map((p) => (
                    <tr key={p.provider} className="border-border border-t">
                      <th
                        scope="row"
                        className="text-left font-mono font-normal"
                      >
                        {p.provider}
                      </th>
                      <td className="num text-right">
                        {rate(p.tokensInPerMinute)}
                      </td>
                      <td className="num text-right">
                        {rate(p.tokensOutPerMinute)}
                      </td>
                      <td className="num text-right">
                        {p.spendPerHour.toFixed(2)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <dl className="text-body-md grid grid-cols-1 gap-2 sm:grid-cols-3">
            {tools.map(([label, value]) => (
              <div key={label}>
                <dt className="text-body-sm text-on-surface-variant">
                  {label}
                </dt>
                <dd
                  className={`num ${label !== 'MCP calls / min' && value > 0 ? 'text-warning' : 'text-on-surface'}`}
                >
                  {rate(value)}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      </PanelState>
    </Panel>
  );
}
