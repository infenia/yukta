// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { TONE, type Tone } from './tones';
import { Tip } from '@/components/ui/tooltip';

export interface HeatCell {
  label: string;
  /** 0 to 1: how busy the cell was relative to the busiest */
  volume: number;
  /** 0 to 1: the share of failed runs */
  failureRatio?: number;
}

/** One cell per interval, shaded by volume; a share of failures tints it red and hatches it. */
export function HeatStrip({
  cells,
  columns,
  label,
}: {
  cells: HeatCell[];
  columns: number;
  label: string;
}) {
  return (
    <div
      role="img"
      aria-label={label}
      className="grid gap-px"
      style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
    >
      {cells.map((c, i) => {
        const failing = !!c.failureRatio && c.failureRatio > 0.05;
        const base = failing ? 'var(--destructive)' : 'var(--chart-1)';
        return (
          <Tip label={c.label}>
            <span
              key={i}
              data-failing={failing || undefined}
              className="bg-muted aspect-square rounded-[2px]"
              style={{
                backgroundColor:
                  c.volume > 0
                    ? `color-mix(in oklab, ${base} ${Math.round(15 + c.volume * 85)}%, transparent)`
                    : undefined,
                // a hatch on top, so a failing minute does not rest on red alone
                backgroundImage:
                  c.volume > 0 && failing
                    ? 'repeating-linear-gradient(45deg, transparent 0 2px, color-mix(in oklab, var(--foreground) 45%, transparent) 2px 3px)'
                    : undefined,
              }}
            />
          </Tip>
        );
      })}
    </div>
  );
}

export type MatrixState = 'ok' | 'stale' | 'down' | 'unknown';

const MATRIX_TONE: Record<MatrixState, Tone> = {
  ok: 'success',
  stale: 'warning',
  down: 'danger',
  unknown: 'neutral',
};

/** The marker's shape differs per state, so the state does not rest on colour alone. */
const MATRIX_SHAPE: Record<MatrixState, string> = {
  ok: 'rounded-full bg-current',
  stale: 'rotate-45 bg-current',
  down: 'rounded-sm bg-current',
  unknown: 'rounded-full ring-2 ring-current',
};

/** A heartbeat grid: one dot per node; a healthy one pulses, a stale one turns amber, then red. */
export function StatusMatrix({
  items,
}: {
  items: { id: string; label: string; state: MatrixState }[];
}) {
  return (
    <ul className="flex flex-wrap gap-2">
      {items.map(({ id, label, state }) => (
        <Tip label={`${label}: ${state}`}>
          <li
            key={id}
            className={`${TONE[MATRIX_TONE[state]]} text-label-sm text-on-surface flex items-center gap-1.5`}
          >
            <span
              aria-hidden="true"
              className={`size-2.5 ${MATRIX_SHAPE[state]} ${state === 'ok' ? 'motion-safe:animate-pulse' : ''}`}
            />
            {label}
            <span className="sr-only">{state}</span>
          </li>
        </Tip>
      ))}
    </ul>
  );
}

export interface Annotation {
  at: number;
  label: string;
  kind?: Tone;
}

/**
 * Markers laid over a time chart, `from` to `to` in epoch milliseconds, so a control command or
 * a supervisor's step shows where it happened. Put it on top of the chart in a relative box.
 */
export function ChartAnnotations({
  from,
  to,
  items,
  onSelect,
}: {
  from: number;
  to: number;
  items: Annotation[];
  onSelect?: (item: Annotation) => void;
}) {
  const span = to - from;
  return (
    <div className="pointer-events-none absolute inset-0">
      {span > 0 &&
        items
          .filter((a) => a.at >= from && a.at <= to)
          .map((a) => (
            <Tip label={a.label}>
              <button
                key={`${a.at}-${a.label}`}
                type="button"
                aria-label={a.label}
                onClick={() => onSelect?.(a)}
                style={{ left: `${((a.at - from) / span) * 100}%` }}
                className={`${TONE[a.kind ?? 'attention']} pointer-events-auto absolute inset-y-0 w-2 -translate-x-1/2 cursor-pointer after:absolute after:inset-y-0 after:left-1/2 after:w-px after:bg-current`}
              />
            </Tip>
          ))}
    </div>
  );
}
