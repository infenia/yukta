// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { sparkPoints, TONE, type Tone } from './tones';

export function Sparkline({
  values,
  tone = 'primary',
  small = false,
}: {
  values: number[];
  tone?: Tone;
  small?: boolean;
}) {
  return (
    <svg
      viewBox="0 -2 100 28"
      preserveAspectRatio="none"
      aria-hidden="true"
      className={`${TONE[tone]} ${small ? 'h-5' : 'h-7'} w-full`}
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
 * A headline value with its change since the start of the range and a sparkline under it.
 * `compact` is the dense form of the Control page: less padding, a short sparkline and, if given,
 * a `hint` under the value and an `adornment` (a small ring) beside it.
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
}: {
  label: string;
  value: string;
  delta?: string;
  deltaTone?: Tone;
  series?: number[];
  compact?: boolean;
  hint?: string;
  adornment?: ReactNode;
  /** what the number means, shown as the browser's tooltip */
  title?: string;
}) {
  return (
    <div
      title={title}
      className={`border-border bg-card flex min-w-0 flex-col rounded-xl border ${compact ? 'gap-0.5 px-3 py-2.5' : 'gap-1 p-4'}`}
    >
      <span className="text-label-md text-on-surface-variant truncate">
        {label}
      </span>
      <div className="flex items-center gap-2">
        {adornment}
        <div className="flex min-w-0 items-baseline gap-2">
          <span className="text-headline-md num text-on-surface">{value}</span>
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
      {series.length > 0 && <Sparkline values={series} small={compact} />}
    </div>
  );
}
