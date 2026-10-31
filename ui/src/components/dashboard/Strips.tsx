// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { TONE, type Tone } from './tones';

export interface HeatCell {
  label: string;
  /** 0 to 1: how busy the cell was relative to the busiest */
  volume: number;
  /** 0 to 1: the share of failed runs */
  failureRatio?: number;
}

/** One cell per interval, shaded by volume and tinted red by the share that failed. */
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
        const base =
          c.failureRatio && c.failureRatio > 0.05
            ? 'var(--destructive)'
            : 'var(--chart-1)';
        return (
          <span
            key={i}
            title={c.label}
            className="aspect-square rounded-[2px] bg-muted"
            style={{
              backgroundColor:
                c.volume > 0
                  ? `color-mix(in oklab, ${base} ${Math.round(15 + c.volume * 85)}%, transparent)`
                  : undefined,
            }}
          />
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
        <li
          key={id}
          title={`${label}: ${state}`}
          className={`${TONE[MATRIX_TONE[state]]} flex items-center gap-1.5 text-label-sm text-on-surface`}
        >
          <span
            aria-hidden="true"
            className={`size-2.5 ${MATRIX_SHAPE[state]} ${state === 'ok' ? 'motion-safe:animate-pulse' : ''}`}
          />
          {label}
          <span className="sr-only">{state}</span>
        </li>
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
            <button
              key={`${a.at}-${a.label}`}
              type="button"
              title={a.label}
              aria-label={a.label}
              onClick={() => onSelect?.(a)}
              style={{ left: `${((a.at - from) / span) * 100}%` }}
              className={`${TONE[a.kind ?? 'attention']} pointer-events-auto absolute inset-y-0 w-2 -translate-x-1/2 cursor-pointer after:absolute after:inset-y-0 after:left-1/2 after:w-px after:bg-current`}
            />
          ))}
    </div>
  );
}
