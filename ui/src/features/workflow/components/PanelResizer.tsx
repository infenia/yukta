// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { KeyboardEvent, PointerEvent } from 'react';

const STEP = 24;

/**
 * A drag handle on the left edge of a right-hand panel: drag left to widen it, right to narrow it.
 * Arrow keys do the same (Home/End jump to the limits), so it works without a pointer.
 */
export function PanelResizer({
  width,
  min,
  max,
  onWidth,
}: {
  width: number;
  min: number;
  max: number;
  onWidth: (width: number) => void;
}) {
  const clamp = (value: number) => Math.min(max, Math.max(min, value));

  const onPointerDown = (event: PointerEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = width;
    const move = (e: globalThis.PointerEvent) =>
      onWidth(clamp(startWidth + startX - e.clientX));
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      document.body.style.removeProperty('cursor');
      document.body.style.removeProperty('user-select');
    };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
  };

  const onKeyDown = (event: KeyboardEvent) => {
    const next: Record<string, number> = {
      ArrowLeft: width + STEP,
      ArrowRight: width - STEP,
      Home: max,
      End: min,
    };
    if (event.key in next) {
      event.preventDefault();
      onWidth(clamp(next[event.key]));
    }
  };

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize panel"
      aria-valuenow={width}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      className="group absolute inset-y-0 left-0 z-20 w-2 -translate-x-1/2 cursor-col-resize touch-none outline-none"
    >
      <span
        aria-hidden="true"
        className="bg-primary transition-standard absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100"
      />
    </div>
  );
}
