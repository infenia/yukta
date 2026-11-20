// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { pluginIcon, pluginLabel } from '@/features/workflow/lib/pluginIcons';
import type { PluginCategory } from '@/features/workflow/types/workflow';
import type { Flow } from '../lib/flowLayout';

const STEP_MS = 520;

const reducedMotion = () =>
  typeof window !== 'undefined' &&
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

/**
 * A workflow as a row of icon chips that replays a run while `playing`: each wave of nodes lights up
 * (running, then done) and its edges flow, then it loops. The flow comes from the template itself,
 * so any template, including one added later, animates without extra work.
 */
export function TemplateFlow({
  flow,
  categories,
  playing,
  labels = false,
  off,
}: {
  flow: Flow;
  categories: Record<string, string>;
  playing: boolean;
  /** Name each node under its icon (the large preview). */
  labels?: boolean;
  /** Plugin types the server has switched off: drawn dashed and dimmed. */
  off?: ReadonlySet<string>;
}) {
  const active = playing && flow.waves > 0 && !reducedMotion();
  // 0..waves-1 is the running wave; `waves` holds the finished state for one beat
  const [step, setStep] = useState(0);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(
      () => setStep((s) => (s >= flow.waves ? 0 : s + 1)),
      STEP_MS
    );
    return () => {
      clearInterval(id);
      setStep(0);
    };
  }, [active, flow.waves]);
  const shown = active ? step : -1;

  const waveOf = new Map(flow.nodes.map((n) => [n.id, n.wave]));
  const at = (id: string) => waveOf.get(id) as number;

  return (
    <div
      data-testid="template-flow"
      className="relative size-full"
      aria-hidden="true"
    >
      <svg
        className="absolute inset-0 size-full"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
      >
        {flow.edges.map((e) => {
          const [a, b] = [e.source, e.target].map((id) =>
            flow.nodes.find((n) => n.id === id)
          );
          const flowing =
            shown >= 0 && at(e.source) < shown && at(e.target) >= shown;
          const done = shown >= 0 && at(e.target) < shown;
          return (
            <line
              key={`${e.source}>${e.target}`}
              x1={a?.x}
              y1={a?.y}
              x2={b?.x}
              y2={b?.y}
              vectorEffect="non-scaling-stroke"
              strokeWidth={flowing ? 2 : 1.25}
              strokeLinecap="round"
              className={cn(
                'transition-standard',
                flowing
                  ? 'flow-edge-live stroke-attention'
                  : done
                    ? 'stroke-success/60'
                    : 'stroke-border'
              )}
            />
          );
        })}
      </svg>
      {flow.nodes.map((n) => {
        const running = shown >= 0 && n.wave === shown;
        const done = shown >= 0 && n.wave < shown;
        const disabled = off?.has(n.type) ?? false;
        return (
          <Tip
            key={n.id}
            label={
              disabled
                ? `${pluginLabel(n.type)}: not enabled on this server`
                : pluginLabel(n.type)
            }
          >
            <span
              style={{ left: `${n.x}%`, top: `${n.y}%` }}
              className={cn(
                'transition-standard absolute flex -translate-1/2 items-center justify-center rounded-lg border',
                labels ? 'size-10' : 'size-7',
                disabled
                  ? 'border-attention/70 bg-card text-attention/80 border-dashed'
                  : running
                    ? 'border-attention bg-card text-attention ring-attention/25 scale-110 ring-4'
                    : done
                      ? 'border-success/40 text-success bg-[color-mix(in_oklab,var(--success)_12%,var(--card))]'
                      : 'border-border bg-card text-on-surface-variant'
              )}
            >
              <span className="material-symbols-outlined text-base">
                {pluginIcon(
                  n.type,
                  (categories[n.type] as PluginCategory | undefined) ?? null
                )}
              </span>
              {labels && (
                <span className="text-label-sm text-on-surface-variant absolute top-full left-1/2 mt-2 -translate-x-1/2 whitespace-nowrap">
                  {pluginLabel(n.type)}
                </span>
              )}
            </span>
          </Tip>
        );
      })}
    </div>
  );
}
