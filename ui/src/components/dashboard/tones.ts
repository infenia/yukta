// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export type Tone =
  'primary' | 'success' | 'warning' | 'attention' | 'danger' | 'neutral';

/** Graphics draw in `currentColor`; the tone sets it from a design token. */
export const TONE: Record<Tone, string> = {
  primary: 'text-primary',
  success: 'text-success',
  warning: 'text-warning',
  attention: 'text-attention',
  danger: 'text-destructive',
  neutral: 'text-muted-foreground',
};

export const clampPercent = (value: number, max: number) =>
  max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0;

/** The points of a line scaled into a `w` by `h` box; a flat or empty series is a level line. */
export function sparkPoints(values: number[], w = 100, h = 24): string {
  if (values.length === 0) {
    return '';
  }
  const min = Math.min(...values);
  const span = Math.max(...values) - min || 1;
  const step = values.length > 1 ? w / (values.length - 1) : 0;
  return values
    .map(
      (v, i) =>
        `${(i * step).toFixed(1)},${(h - ((v - min) / span) * h).toFixed(1)}`
    )
    .join(' ');
}
