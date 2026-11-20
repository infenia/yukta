// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useDashboardParams, useMetricStream } from '@/features/metrics/hooks';
import { SystemPanel } from './HealthPanels';
import { CapacityPanel } from './CapacityPanel';
import { OverheadPanel, ThroughputPanel } from './ChartPanels';
import { CommandStrip } from './CommandStrip';
import { LiveRunsPanel, NodesPanel } from './RunPanels';

/** How long each group of panels stays on screen. */
export const KIOSK_CYCLE_MS = 15_000;

/**
 * Full-screen wall display: the command strip stays, the panels cycle in groups on a timer, and the
 * type is large. Escape (or the button) leaves it; the cycle pauses while a pointer is over it.
 */
export function KioskView({ onExit }: { onExit: () => void }) {
  const { scope, range, live } = useDashboardParams();
  useMetricStream(live);
  const [page, setPage] = useState(0);
  const [held, setHeld] = useState(false);
  // the parent's callback is a new function on every render; the fullscreen effect must not re-run
  const exit = useRef(onExit);
  useEffect(() => {
    exit.current = onExit;
  });
  const groups = [
    <div key="runs" className="grid items-start gap-4 lg:grid-cols-2">
      <ThroughputPanel scope={scope} range={range} live={live} />
      <LiveRunsPanel scope={scope} live={live} />
    </div>,
    <div key="nodes" className="grid items-start gap-4 lg:grid-cols-2">
      <NodesPanel scope={scope} range={range} live={live} />
      <OverheadPanel scope={scope} range={range} live={live} />
    </div>,
    <div key="health" className="grid items-start gap-4 lg:grid-cols-2">
      <CapacityPanel live={live} />
      <SystemPanel scope={scope} live={live} />
    </div>,
  ];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onExit();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onExit]);

  // Fullscreen where the browser allows it; a refusal leaves the overlay, which covers the app anyway.
  // Leaving fullscreen by the browser's own Esc leaves the kiosk too.
  useEffect(() => {
    const root = document.documentElement;
    void root.requestFullscreen?.().catch(() => undefined);
    const onChange = () => {
      if (!document.fullscreenElement) exit.current();
    };
    document.addEventListener('fullscreenchange', onChange);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('fullscreenchange', onChange);
      document.body.style.overflow = overflow;
      if (document.fullscreenElement) void document.exitFullscreen();
    };
  }, []);

  useEffect(() => {
    if (held) return;
    const id = window.setInterval(
      () => setPage((p) => (p + 1) % groups.length),
      KIOSK_CYCLE_MS
    );
    return () => window.clearInterval(id);
  }, [held, groups.length]);

  // In the body, so no stacking context of the page can put it behind the header or the sidebar.
  return createPortal(
    <div
      role="dialog"
      aria-label="Kiosk mode"
      data-testid="kiosk"
      onMouseEnter={() => setHeld(true)}
      onMouseLeave={() => setHeld(false)}
      className="bg-background fixed inset-0 z-60 flex flex-col gap-4 overflow-auto p-3 text-base sm:p-6 sm:text-xl"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 sm:gap-4">
        <div className="min-w-0 flex-1 basis-full sm:basis-0">
          <CommandStrip scope={scope} live={live} />
        </div>
        <button
          type="button"
          onClick={onExit}
          className="border-border text-label-md h-9 shrink-0 rounded-lg border px-3"
        >
          Exit (Esc)
        </button>
      </div>
      <div className="min-h-0 flex-1">{groups[page]}</div>
      <div
        className="flex justify-center gap-2"
        role="group"
        aria-label="Panel group"
      >
        {groups.map((g, i) => (
          <button
            key={g.key}
            type="button"
            aria-label={`Group ${i + 1}`}
            aria-pressed={i === page}
            onClick={() => setPage(i)}
            className={`h-2.5 w-8 rounded-full ${i === page ? 'bg-primary' : 'bg-muted'}`}
          />
        ))}
      </div>
    </div>,
    document.body
  );
}
