// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { ArcGauge, MeterBar } from '@/components/dashboard/Gauges';
import { Panel } from '@/components/dashboard/Tile';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { useEdition } from '@/features/edition/hooks/useEdition';
import { useWorkers } from '../api/workers';
import { PanelState } from './PanelState';
import {
  formatMs,
  healthConcerns,
  longestGcPause,
  sumOf,
  usedOfBytes,
} from './format';

function WorkersBody({ live }: { live: boolean }) {
  const workers = useWorkers(live);
  const community = useEdition().data?.edition === 'COMMUNITY';
  return (
    <PanelState
      isPending={workers.isPending}
      error={workers.error}
      empty={(workers.data ?? []).length === 0}
      inline
      emptyText={
        community
          ? 'No workers connected (remote workers need the Plus edition)'
          : 'No workers connected'
      }
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
  );
}

/** Slots in use per connected worker. */
export function WorkerSlotsPanel({ live = true }: { live?: boolean }) {
  return (
    <Panel title="Workers">
      <WorkersBody live={live} />
    </Panel>
  );
}

function VitalsBody() {
  const snapshot = useMetricSnapshot();
  const values = snapshot.data;
  const heapUsed = sumOf(values, 'jvm.memory.used{area=heap');
  const heapMax = sumOf(values, 'jvm.memory.max{area=heap');
  const heapCommitted = sumOf(values, 'jvm.memory.committed{area=heap');
  // the machine's (or container's) memory, which the heap is only a part of
  const hostTotal = values?.['yukta.system.memory.total'] ?? 0;
  const hostUsed = hostTotal - (values?.['yukta.system.memory.free'] ?? 0);
  const cpu = (values?.['process.cpu.usage'] ?? 0) * 100;
  const threads = values?.['jvm.threads.live'] ?? 0;
  return (
    <PanelState
      isPending={snapshot.isPending}
      error={snapshot.error}
      empty={!values || Object.keys(values).length === 0}
      emptyText="No samples yet"
      onRetry={() => void snapshot.refetch()}
    >
      <div className="grid grid-cols-2 gap-2">
        {[
          {
            name: 'JVM heap',
            caption: usedOfBytes(heapUsed, heapMax),
            value: heapUsed,
            max: Math.max(1, heapMax),
            text: `${Math.round((heapUsed / Math.max(1, heapMax)) * 100)}%`,
          },
          ...(hostTotal > 0
            ? [
                {
                  name: 'Host memory',
                  caption: usedOfBytes(hostUsed, hostTotal),
                  value: hostUsed,
                  max: hostTotal,
                  text: `${Math.round((hostUsed / hostTotal) * 100)}%`,
                },
              ]
            : []),
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
            <figcaption className="text-body-sm text-on-surface-variant text-center wrap-break-word">
              {g.name} · {g.caption}
            </figcaption>
          </figure>
        ))}
      </div>
      <dl className="text-body-sm text-on-surface-variant mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {heapCommitted > 0 && (
          <div>
            <dt>Heap reserved</dt>
            <dd className="num text-on-surface">
              {usedOfBytes(heapCommitted, 0)}
            </dd>
          </div>
        )}
        <div>
          <dt>Longest GC pause</dt>
          <dd className="num text-on-surface">
            {formatMs(longestGcPause(values))}
          </dd>
        </div>
        <div>
          <dt>Dropped events / min</dt>
          <dd className="num text-on-surface">
            {(sumOf(values, 'yukta.tracker.dropped', '|rate') * 60).toFixed(1)}
          </dd>
        </div>
      </dl>
    </PanelState>
  );
}

/** Heap, CPU and threads as gauges, with the worst garbage-collection pause and dropped events. */
export function VitalsPanel() {
  return (
    <Panel title="System">
      <VitalsBody />
    </Panel>
  );
}

/** A subsection of the System panel. */
function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section
      aria-label={title}
      className="border-border flex flex-col gap-1 border-t pt-2"
    >
      <h3 className="text-overline text-on-surface-variant">{title}</h3>
      {children}
    </section>
  );
}

/**
 * The server's vitals and its workers in one card: the workers are one muted line while there
 * are none.
 */
export function SystemPanel({ live }: { live: boolean }) {
  const concerns = healthConcerns(useMetricSnapshot().data);
  return (
    <Panel
      title="System"
      action={
        concerns.length > 0 && (
          <span className="bg-warning/15 text-label-sm text-on-surface rounded-full px-2 py-0.5">
            {concerns.join(' · ')}
          </span>
        )
      }
      info="The server's own health. JVM heap is the Java memory the server may use, not the machine's RAM. CPU is the server process; threads are live Java threads."
    >
      <VitalsBody />
      <Section title="Workers">
        <WorkersBody live={live} />
      </Section>
    </Panel>
  );
}
