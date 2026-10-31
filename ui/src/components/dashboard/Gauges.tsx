// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { clampPercent, TONE, type Tone } from './tones';

interface MeterProps {
  value: number;
  max: number;
  label: string;
  tone?: Tone;
}

const meterAria = ({ value, max, label }: MeterProps) => ({
  role: 'meter' as const,
  'aria-label': label,
  'aria-valuemin': 0,
  'aria-valuemax': max,
  'aria-valuenow': value,
});

/** A half-circle gauge: `value` of `max`, the figure under the arc. */
export function ArcGauge(props: MeterProps) {
  const { value, tone = 'primary' } = props;
  const pct = clampPercent(value, props.max);
  return (
    <div
      {...meterAria(props)}
      className={`${TONE[tone]} relative w-full max-w-40`}
    >
      <svg viewBox="0 0 100 56" aria-hidden="true" className="w-full">
        <path
          d="M 10 50 A 40 40 0 0 1 90 50"
          pathLength={100}
          fill="none"
          strokeWidth={8}
          strokeLinecap="round"
          className="stroke-muted"
        />
        <path
          d="M 10 50 A 40 40 0 0 1 90 50"
          pathLength={100}
          fill="none"
          strokeWidth={8}
          strokeLinecap="round"
          stroke="currentColor"
          strokeDasharray={`${pct} 100`}
          className="transition-[stroke-dasharray] duration-300 motion-reduce:transition-none"
        />
      </svg>
      <span className="text-headline-md num text-on-surface absolute inset-x-0 bottom-0 text-center">
        {value}
      </span>
    </div>
  );
}

/** A full ring showing `value` of `max` as a percentage in its middle. */
export function RingProgress(props: MeterProps) {
  const { tone = 'primary' } = props;
  const pct = clampPercent(props.value, props.max);
  return (
    <div {...meterAria(props)} className={`${TONE[tone]} relative size-20`}>
      <svg
        viewBox="0 0 40 40"
        aria-hidden="true"
        className="size-full -rotate-90"
      >
        <circle
          cx={20}
          cy={20}
          r={16}
          fill="none"
          strokeWidth={4}
          className="stroke-muted"
        />
        <circle
          cx={20}
          cy={20}
          r={16}
          fill="none"
          strokeWidth={4}
          strokeLinecap="round"
          stroke="currentColor"
          pathLength={100}
          strokeDasharray={`${pct} 100`}
          className="transition-[stroke-dasharray] duration-300 motion-reduce:transition-none"
        />
      </svg>
      <span className="text-label-md num text-on-surface absolute inset-0 flex items-center justify-center">
        {Math.round(pct)}%
      </span>
    </div>
  );
}

/** A horizontal bar, such as a worker's slots in use. */
export function MeterBar(props: MeterProps) {
  const { tone = 'primary' } = props;
  return (
    <div
      {...meterAria(props)}
      className="bg-muted h-2 w-full overflow-hidden rounded-full"
    >
      <div
        className={`${TONE[tone]} h-full rounded-full bg-current transition-[width] duration-300 motion-reduce:transition-none`}
        style={{ width: `${clampPercent(props.value, props.max)}%` }}
      />
    </div>
  );
}

export interface DonutSegment {
  label: string;
  value: number;
  tone: Tone;
}

/** Shares of a whole, e.g. a supervisor's decisions; the legend is part of the accessible name. */
export function Donut({ segments }: { segments: DonutSegment[] }) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  let offset = 0;
  return (
    <svg
      viewBox="0 0 40 40"
      role="img"
      aria-label={segments.map((s) => `${s.label} ${s.value}`).join(', ')}
      className="size-20 -rotate-90"
    >
      <circle
        cx={20}
        cy={20}
        r={14}
        fill="none"
        strokeWidth={6}
        className="stroke-muted"
      />
      {total > 0 &&
        segments.map((s) => {
          const share = (s.value / total) * 100;
          const circle = (
            <circle
              key={s.label}
              cx={20}
              cy={20}
              r={14}
              fill="none"
              strokeWidth={6}
              pathLength={100}
              stroke="currentColor"
              strokeDasharray={`${share} ${100 - share}`}
              strokeDashoffset={-offset}
              className={TONE[s.tone]}
            />
          );
          offset += share;
          return circle;
        })}
    </svg>
  );
}
