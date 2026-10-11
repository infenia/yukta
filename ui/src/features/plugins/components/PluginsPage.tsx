// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import type { PluginCategory } from '@/features/workflow/types/workflow';
import { usePlugin, usePlugins } from '../hooks/usePlugins';
import { PluginCanvasPreview } from './PluginCanvasPreview';

const CATEGORIES: { key: PluginCategory; label: string }[] = [
  { key: 'TRIGGER', label: 'Triggers' },
  { key: 'PROCESSOR', label: 'Processors' },
  { key: 'TERMINAL', label: 'Terminals' },
];

function PluginDetail({ type }: { type: string }) {
  const { data: plugin, isPending, error } = usePlugin(type);
  if (isPending) return <p className="text-on-surface-variant">Loading…</p>;
  if (error || !plugin) {
    return (
      <p role="alert" className="text-error">
        Failed to load {type}.
      </p>
    );
  }

  const ports =
    plugin.outputPorts.length > 0
      ? plugin.outputPorts.join(', ')
      : plugin.category === 'TERMINAL'
        ? 'None (terminal)'
        : 'default';

  return (
    <article className="flex flex-col gap-4">
      <header>
        <h2 className="text-xl font-semibold text-on-surface">{plugin.type}</h2>
        <span className="text-xs tracking-wide text-on-surface-variant uppercase">
          {plugin.category}
        </span>
      </header>
      {plugin.description && <p className="text-on-surface">{plugin.description}</p>}
      <section>
        <h3 className="mb-1 text-sm font-semibold text-on-surface">Output ports</h3>
        <p className="text-sm text-on-surface-variant">{ports}</p>
      </section>
      {plugin.usagePattern && (
        <section>
          <h3 className="mb-1 text-sm font-semibold text-on-surface">Usage</h3>
          <pre className="overflow-x-auto border border-outline-variant bg-surface-container-low p-3 text-xs whitespace-pre-wrap">
            {plugin.usagePattern}
          </pre>
        </section>
      )}
      <section>
        <h3 className="mb-1 text-sm font-semibold text-on-surface">On the canvas</h3>
        <PluginCanvasPreview plugin={plugin} />
      </section>
    </article>
  );
}

export function PluginsPage() {
  const { data: plugins = [], isPending, error } = usePlugins();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');

  if (isPending) return <p className="p-6 text-on-surface-variant">Loading plugins…</p>;
  if (error) {
    return (
      <p role="alert" className="p-6 text-error">
        Failed to load plugins.
      </p>
    );
  }

  const selected = params.get('type') ?? plugins[0]?.type ?? null;
  const needle = filter.trim().toLowerCase();

  return (
    <div className="flex flex-col gap-6 p-6 md:flex-row">
      <nav aria-label="Plugins" className="flex shrink-0 flex-col gap-4 md:w-72">
        <input
          type="search"
          aria-label="Filter plugins"
          placeholder="Filter plugins"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="border border-outline-variant bg-surface px-3 py-2 text-sm text-on-surface"
        />
        {CATEGORIES.map(({ key, label }) => {
          const items = plugins.filter(
            (p) => p.category === key && p.type.toLowerCase().includes(needle)
          );
          if (items.length === 0) return null;
          return (
            <section key={key}>
              <h2 className="mb-1 text-xs font-semibold tracking-wide text-on-surface-variant uppercase">
                {label}
              </h2>
              <ul>
                {items.map((p) => (
                  <li key={p.type}>
                    <button
                      type="button"
                      aria-current={p.type === selected}
                      onClick={() => setParams({ type: p.type })}
                      className={cn(
                        'w-full px-3 py-1.5 text-left text-sm text-on-surface hover:bg-surface-container-low',
                        p.type === selected && 'bg-surface-container font-semibold'
                      )}
                    >
                      {p.type}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </nav>
      <div className="min-w-0 flex-1">
        {selected ? <PluginDetail type={selected} /> : <p>No plugins available.</p>}
      </div>
    </div>
  );
}
