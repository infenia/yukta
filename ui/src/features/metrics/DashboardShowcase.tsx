// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { PageHeader } from '@/components/layout/PageHeader';
import { Panel } from '@/components/dashboard/Tile';
import {
  ArcGauge,
  Donut,
  MeterBar,
  RingProgress,
} from '@/components/dashboard/Gauges';
import { KpiTile } from '@/components/dashboard/KpiTile';
import { Knob } from '@/components/dashboard/Knob';
import {
  ChartAnnotations,
  HeatStrip,
  StatusMatrix,
} from '@/components/dashboard/Strips';
import { ScopeBar } from './ScopeBar';

const SERIES = [3, 5, 4, 8, 6, 9, 7, 12, 10, 14, 11, 15];
const NOW = Date.UTC(2026, 0, 1, 12);
const CELLS = Array.from({ length: 120 }, (_, i) => ({
  label: `minute ${i}`,
  volume: ((i * 37) % 100) / 100,
  failureRatio: i % 17 === 0 ? 0.5 : 0,
}));

/** Every dashboard primitive on fixed sample data, so light and dark can be reviewed together. */
export function DashboardShowcase() {
  const [limit, setLimit] = useState(8);
  return (
    <div
      className="flex flex-col gap-4 p-4 sm:p-6"
      data-testid="dashboard-showcase"
    >
      <PageHeader title="Dashboard components" />
      <ScopeBar />
      <div className="grid gap-4 sm:grid-cols-3">
        <KpiTile
          label="Throughput / min"
          value="42"
          delta="+12%"
          deltaTone="success"
          series={SERIES}
        />
        <KpiTile
          label="p95 duration"
          value="1.8 s"
          delta="+0.3 s"
          deltaTone="danger"
          series={[...SERIES].reverse()}
        />
        <KpiTile label="Pending approvals" value="3" />
      </div>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Panel title="Capacity">
          <ArcGauge
            label="Running of limit"
            value={limit}
            max={16}
            tone="attention"
          />
          <Knob
            label="Max concurrent"
            value={limit}
            min={0}
            max={16}
            onChange={setLimit}
          />
        </Panel>
        <Panel title="Success rate">
          <RingProgress
            label="Success rate, 24 h"
            value={97}
            max={100}
            tone="success"
          />
        </Panel>
        <Panel title="Decisions">
          <Donut
            segments={[
              { label: 'Applied', value: 12, tone: 'success' },
              { label: 'Rejected', value: 3, tone: 'danger' },
              { label: 'Dry-run', value: 5, tone: 'primary' },
            ]}
          />
        </Panel>
        <Panel title="Worker slots">
          <MeterBar label="Worker 1 slots" value={6} max={8} tone="warning" />
          <MeterBar label="Worker 2 slots" value={2} max={8} />
        </Panel>
      </div>
      <Panel title="Runs, last 2 hours">
        <HeatStrip cells={CELLS} columns={60} label="Runs per minute" />
        <div className="relative h-8">
          <ChartAnnotations
            from={NOW - 3_600_000}
            to={NOW}
            items={[{ at: NOW - 1_200_000, label: 'Paused by supervisor' }]}
          />
        </div>
      </Panel>
      <Panel title="Control bus">
        <StatusMatrix
          items={[
            { id: 'a', label: 'node-a', state: 'ok' },
            { id: 'b', label: 'node-b', state: 'stale' },
            { id: 'c', label: 'node-c', state: 'down' },
          ]}
        />
      </Panel>
    </div>
  );
}
