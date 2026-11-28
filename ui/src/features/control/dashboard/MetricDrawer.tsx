// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, type ReactNode } from 'react';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';
import type { MetricPoint } from '@/features/metrics/api';

const SeriesChart = lazy(() => import('@/features/metrics/SeriesChart'));

/** What a tile or card opens: a titled series, what it means and how its values read. */
export interface DrillSpec {
  title: string;
  /** the window the series covers, e.g. "last hour" */
  window: string;
  info: ReactNode;
  points: MetricPoint[];
  format?: (value: number) => string;
}

/**
 * A side drawer with the larger chart of one tile: the numbers (latest, average, lowest, highest)
 * over the chart, which has an axis and a tooltip. It reads the points the tile already holds, so
 * opening it costs no request. A drawer, not a modal, so the dashboard stays in view.
 */
export function MetricDrawer({
  spec,
  onClose,
}: {
  spec: DrillSpec;
  onClose: () => void;
}) {
  const { points, format = String } = spec;
  const values = points.map((p) => p.v);
  const stats: [string, number][] =
    values.length === 0
      ? []
      : [
          ['Latest', values[values.length - 1]],
          ['Average', values.reduce((n, v) => n + v, 0) / values.length],
          ['Lowest', Math.min(...values)],
          ['Highest', Math.max(...values)],
        ];
  return (
    <Sheet open onOpenChange={() => onClose()}>
      <SheetContent>
        <SheetTitle>{spec.title}</SheetTitle>
        <p className="text-body-sm text-on-surface-variant">
          {spec.info} Showing the {spec.window}.
        </p>
        {stats.length > 0 && (
          <dl className="grid grid-cols-4 gap-2">
            {stats.map(([name, value]) => (
              <div key={name} className="min-w-0">
                <dt className="text-label-sm text-on-surface-variant">
                  {name}
                </dt>
                <dd className="text-label-md num text-on-surface truncate">
                  {format(value)}
                </dd>
              </div>
            ))}
          </dl>
        )}
        <div className="h-64">
          {points.length > 1 ? (
            <Suspense
              fallback={
                <StateMessage compact tone="loading" title="Loading chart" />
              }
            >
              <SeriesChart points={points} label={spec.title} format={format} />
            </Suspense>
          ) : (
            <StateMessage compact title="Not enough data in this window yet" />
          )}
        </div>
        <SheetClose render={<Button variant="ghost" />}>Close</SheetClose>
      </SheetContent>
    </Sheet>
  );
}
