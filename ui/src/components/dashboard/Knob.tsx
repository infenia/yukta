// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef, type KeyboardEvent, type PointerEvent } from 'react';

/** Pixels of vertical drag that sweep the whole range. */
const DRAG_RANGE_PX = 160;

/**
 * A rotary control: drag up or down, or use the arrow, Page, Home and End keys. It reports every
 * change; the caller decides when to send the value (e.g. on `onCommit`, the release of a drag).
 */
export function Knob({
  value,
  min,
  max,
  step = 1,
  label,
  valueText,
  onChange,
  onCommit,
  disabled = false,
  title,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  label: string;
  valueText?: string;
  onChange: (value: number) => void;
  onCommit?: (value: number) => void;
  /** Shown but not turnable, e.g. for a key whose role may not change what it sets. */
  disabled?: boolean;
  title?: string;
}) {
  const drag = useRef<{ y: number; start: number } | null>(null);
  const snap = (v: number) =>
    Math.min(max, Math.max(min, Math.round((v - min) / step) * step + min));
  const angle = max > min ? -135 + ((value - min) / (max - min)) * 270 : -135;

  const onKeyDown = (e: KeyboardEvent) => {
    const big = Math.max(step, (max - min) / 10);
    const next: Record<string, number> = {
      ArrowUp: value + step,
      ArrowRight: value + step,
      ArrowDown: value - step,
      ArrowLeft: value - step,
      PageUp: value + big,
      PageDown: value - big,
      Home: min,
      End: max,
    };
    if (!disabled && e.key in next) {
      e.preventDefault();
      const v = snap(next[e.key]);
      onChange(v);
      onCommit?.(v);
    }
  };
  const onPointerMove = (e: PointerEvent) => {
    if (!disabled && drag.current) {
      const { y, start } = drag.current;
      onChange(snap(start + ((y - e.clientY) / DRAG_RANGE_PX) * (max - min)));
    }
  };

  return (
    <div
      role="slider"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled || undefined}
      title={title}
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-valuetext={valueText}
      aria-orientation="vertical"
      onKeyDown={onKeyDown}
      onPointerDown={(e) => {
        if (disabled) return;
        e.currentTarget.setPointerCapture?.(e.pointerId);
        drag.current = { y: e.clientY, start: value };
      }}
      onPointerMove={onPointerMove}
      onPointerUp={() => {
        if (drag.current) {
          drag.current = null;
          onCommit?.(value);
        }
      }}
      className={`focus-visible:ring-primary/40 relative size-20 touch-none rounded-full outline-none focus-visible:ring-2 ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-ns-resize'}`}
    >
      <svg viewBox="0 0 40 40" aria-hidden="true" className="size-full">
        <circle
          cx={20}
          cy={20}
          r={17}
          className="fill-card stroke-border"
          strokeWidth={2}
        />
        <line
          x1={20}
          y1={20}
          x2={20}
          y2={6}
          strokeWidth={3}
          strokeLinecap="round"
          className="stroke-primary"
          transform={`rotate(${angle} 20 20)`}
        />
      </svg>
    </div>
  );
}
