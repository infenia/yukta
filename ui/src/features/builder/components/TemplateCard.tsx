// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { pluginIcon, pluginLabel } from '@/features/workflow/lib/pluginIcons';
import type { PluginCategory } from '@/features/workflow/types/workflow';
import { getTemplate } from '../api';
import { flowLayout, type Flow } from '../lib/flowLayout';
import type { TemplateSummary } from '../types';
import { NeedsPlugins } from './NeedsPlugins';
import { TemplateFlow } from './TemplateFlow';

const MAX_CHIPS = 4;

const BLANK_FLOW = flowLayout(
  [
    { nodeId: 'in', type: 'API_TRIGGER' },
    { nodeId: 'out', type: 'CONSOLE_TERMINAL' },
  ],
  [{ source: 'in', target: 'out' }]
);

/** The flow a template's first workflow draws; undefined while it loads. */
function useTemplateFlow(id: string | null): Flow | undefined {
  const detail = useQuery({
    queryKey: ['template', id],
    queryFn: () => getTemplate(id as string),
    enabled: id !== null,
    staleTime: Infinity,
  });
  return useMemo(() => {
    const workflow = Object.values(detail.data?.session.workflows ?? {})[0];
    return workflow && flowLayout(workflow.nodes, workflow.edges);
  }, [detail.data]);
}

/**
 * One start in the gallery: a neutral card with a live preview of the workflow on top (it replays
 * a run while hovered or focused), the title, summary and plugin types below.
 */
export function TemplateCard({
  template,
  categories,
  off,
  onUse,
  onPreview,
}: {
  /** Null is the blank session. */
  template: TemplateSummary | null;
  categories: Record<string, string>;
  /** Plugin types the server has switched off. */
  off: ReadonlySet<string>;
  onUse: () => void;
  onPreview?: () => void;
}) {
  const [playing, setPlaying] = useState(false);
  const loaded = useTemplateFlow(template?.id ?? null);
  const flow = template ? loaded : BLANK_FLOW;
  const types = template?.pluginTypes ?? [];
  const missing = types.filter((type) => off.has(type));

  return (
    <li
      onMouseEnter={() => setPlaying(true)}
      onMouseLeave={() => setPlaying(false)}
      onFocus={() => setPlaying(true)}
      onBlur={() => setPlaying(false)}
      className="group/card border-border bg-card shadow-card transition-standard focus-within:shadow-raised hover:shadow-raised flex flex-col overflow-hidden rounded-2xl border hover:-translate-y-0.5 hover:border-[color-mix(in_oklch,var(--foreground)_22%,var(--border))]"
    >
      <div
        className="border-border bg-muted/40 relative h-36 border-b px-5 py-4"
        style={{
          backgroundImage:
            'radial-gradient(color-mix(in oklch, var(--foreground) 14%, transparent) 1px, transparent 1px)',
          backgroundSize: '14px 14px',
        }}
      >
        {flow ? (
          <TemplateFlow
            flow={flow}
            categories={categories}
            off={off}
            playing={playing}
          />
        ) : (
          <div className="bg-muted size-full animate-pulse rounded-lg" />
        )}
        <span className="border-border bg-card/80 text-overline text-on-surface-variant absolute top-2 right-2 inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 backdrop-blur-sm">
          {playing ? (
            <>
              <span className="live-dot bg-attention size-1.5 rounded-full" />
              Running
            </>
          ) : (
            (flow?.nodes.length ?? 0) > 0 && `${flow?.nodes.length} nodes`
          )}
        </span>
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <h3 className="text-label-md text-on-surface">
          {template?.title ?? 'Blank session'}
        </h3>
        <p className="text-body-sm text-on-surface-variant line-clamp-3">
          {template?.summary ??
            'A trigger wired to a console terminal. Build the rest yourself.'}
        </p>
        {types.length > 0 && (
          <ul className="flex flex-wrap gap-1" aria-label="Plugin types">
            {types.slice(0, MAX_CHIPS).map((type) => (
              <Tip
                key={type}
                label={off.has(type) ? 'Not enabled on this server' : null}
              >
                <li
                  className={cn(
                    'border-border text-on-surface-variant inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs',
                    off.has(type) &&
                      'border-attention/60 text-attention border-dashed line-through'
                  )}
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-sm"
                  >
                    {pluginIcon(
                      type,
                      (categories[type] as PluginCategory | undefined) ?? null
                    )}
                  </span>
                  {pluginLabel(type)}
                </li>
              </Tip>
            ))}
            {types.length > MAX_CHIPS && (
              <Tip label={types.slice(MAX_CHIPS).map(pluginLabel).join(', ')}>
                <li className="text-on-surface-variant rounded-full px-2 py-0.5 text-xs">
                  +{types.length - MAX_CHIPS} more
                </li>
              </Tip>
            )}
          </ul>
        )}
        <NeedsPlugins types={missing} brief />
        <div className="mt-auto flex items-center gap-2 pt-3">
          <Button
            size="sm"
            variant="outline"
            onClick={onUse}
            className="transition-standard group-focus-within/card:bg-primary group-focus-within/card:text-primary-foreground group-hover/card:bg-primary group-hover/card:text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground dark:hover:bg-primary/90 cursor-pointer group-focus-within/card:border-transparent group-hover/card:border-transparent hover:border-transparent"
          >
            {template ? 'Use template' : 'Start blank'}
          </Button>
          {onPreview && (
            <Button
              size="sm"
              variant="ghost"
              onClick={onPreview}
              className="cursor-pointer"
            >
              Preview
            </Button>
          )}
        </div>
      </div>
    </li>
  );
}
