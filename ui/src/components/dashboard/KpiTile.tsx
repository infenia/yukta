// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { sparkPoints, TONE, type Tone } from './tones';
import { InfoTip } from './Tile';
import { Tip } from '@/components/ui/tooltip';

export function Sparkline({
  values,
  tone = 'primary',
}: {
  values: number[];
  tone?: Tone;
}) {
  return (
    <svg
      viewBox="0 -2 100 28"
      preserveAspectRatio="none"
      aria-hidden="true"
      className={`${TONE[tone]} h-5 w-full`}
    >
      <polyline
        points={sparkPoints(values)}
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
}

/**
 * A headline value with its change since the start of the range. `compact` is the dense form of
 * the Dashboard: the label, value and `hint` on the left and the sparkline on the right, so no
 * space sits empty. With `onDrill` the sparkline is a button that opens a larger chart; `info`
 * adds an "i" that explains the number; `title` is the older hover tooltip.
 */
export function KpiTile({
  label,
  value,
  delta,
  deltaTone = 'neutral',
  series = [],
  compact = false,
  hint,
  adornment,
  title,
  info,
  onDrill,
  tone = 'primary',
}: {
  label: string;
  value: string;
  delta?: string;
  deltaTone?: Tone;
  series?: number[];
  compact?: boolean;
  hint?: string;
  adornment?: ReactNode;
  /** what the number means, shown as a tooltip */
  title?: string;
  /** what the number means, behind an "i" button */
  info?: ReactNode;
  /** opens the larger chart; makes the sparkline a button */
  onDrill?: () => void;
  /** the sparkline colour */
  tone?: Tone;
}) {
  // a line needs two points; one point or none is left out rather than drawn as a dot
  const spark = series.length > 1;
  return (
    <Tip label={title}>
      <div
        className={`border-border bg-card flex min-w-0 items-stretch gap-2 rounded-xl border ${compact ? 'px-3 py-2.5' : 'p-4'}`}
      >
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex min-w-0 items-center gap-1">
            <span className="text-label-md text-on-surface-variant truncate">
              {label}
            </span>
            {info && <InfoTip title={label}>{info}</InfoTip>}
          </div>
          <div className="flex items-center gap-2">
            {adornment}
            <div className="flex min-w-0 items-baseline gap-2">
              <span className="text-headline-md num text-on-surface">
                {value}
              </span>
              {delta && (
                <span className={`${TONE[deltaTone]} text-label-sm num`}>
                  {delta}
                </span>
              )}
            </div>
          </div>
          {hint && (
            <span className="text-body-sm text-on-surface-variant truncate">
              {hint}
            </span>
          )}
        </div>
        {spark &&
          (onDrill ? (
            <button
              type="button"
              onClick={onDrill}
              aria-label={`Open ${label} chart`}
              className="hover:bg-muted w-2/5 max-w-32 shrink-0 cursor-pointer self-end rounded-md p-1"
            >
              <Sparkline values={series} tone={tone} />
            </button>
          ) : (
            <div className="w-2/5 max-w-32 shrink-0 self-end p-1">
              <Sparkline values={series} tone={tone} />
            </div>
          ))}
      </div>
    </Tip>
  );
}
