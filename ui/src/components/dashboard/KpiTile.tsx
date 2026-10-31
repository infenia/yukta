// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sparkPoints, TONE, type Tone } from './tones';

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
      className={`${TONE[tone]} h-7 w-full`}
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

/** A headline value with its change since the start of the range and a sparkline under it. */
export function KpiTile({
  label,
  value,
  delta,
  deltaTone = 'neutral',
  series = [],
}: {
  label: string;
  value: string;
  delta?: string;
  deltaTone?: Tone;
  series?: number[];
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-xl border border-border bg-card p-4">
      <span className="text-label-md text-on-surface-variant">{label}</span>
      <div className="flex items-baseline gap-2">
        <span className="text-headline-md num text-on-surface">{value}</span>
        {delta && (
          <span className={`${TONE[deltaTone]} text-label-sm num`}>
            {delta}
          </span>
        )}
      </div>
      {series.length > 0 && <Sparkline values={series} />}
    </div>
  );
}
