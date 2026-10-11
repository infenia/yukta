// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Input } from '@/components/ui/input';
import { PageHeader } from '@/components/layout/PageHeader';
import { categoryAccent } from '@/features/workflow/lib/categoryAccent';
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
    return <StateMessage tone="error" title={`Failed to load ${type}.`} />;
  }

  const ports =
    plugin.outputPorts.length > 0
      ? plugin.outputPorts.join(', ')
      : plugin.category === 'TERMINAL'
        ? 'None (terminal)'
        : 'default';

  return (
    <article className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <div className="flex min-w-0 flex-col gap-5">
        <header>
          <h2 className="text-headline-lg text-on-surface">{plugin.type}</h2>
          <span className="text-on-surface-variant text-body-md mt-1 inline-flex items-center gap-2">
            <span
              aria-hidden="true"
              className="size-2 rounded-full"
              style={{ background: categoryAccent(plugin.category) }}
            />
            {plugin.category}
          </span>
        </header>
        {plugin.description && (
          <p className="text-on-surface text-body-md max-w-prose">
            {plugin.description}
          </p>
        )}
        <section>
          <h3 className="text-on-surface text-label-md mb-1.5">Output ports</h3>
          <p className="text-on-surface-variant text-sm">{ports}</p>
        </section>
        {plugin.configSchema && plugin.configSchema.length > 0 && (
          <section>
            <h3 className="text-on-surface text-label-md mb-1.5">
              Configuration
            </h3>
            <table className="border-outline-variant w-full border text-left text-sm">
              <tbody>
                {plugin.configSchema.map((field) => (
                  <tr
                    key={field.name}
                    className="border-outline-variant border-b align-top"
                  >
                    <td className="text-code-sm px-3 py-2">
                      {field.name}
                      {field.required && <span className="text-error"> *</span>}
                    </td>
                    <td className="text-body-sm text-on-surface-variant pr-3">
                      {field.type}
                    </td>
                    <td className="text-body-sm text-on-surface-variant pr-3">
                      {field.description}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
        {plugin.usagePattern && (
          <section>
            <h3 className="text-on-surface text-label-md mb-1.5">Usage</h3>
            <CodeBlock code={plugin.usagePattern} label="usage" />
          </section>
        )}
      </div>
      <section className="min-w-0 xl:sticky xl:top-28 xl:self-start">
        <h3 className="text-on-surface text-label-md mb-1.5">On the canvas</h3>
        <PluginCanvasPreview plugin={plugin} />
      </section>
    </article>
  );
}

export function PluginsPage() {
  const { data: plugins = [], isPending, error } = usePlugins();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');

  if (isPending)
    return (
      <p className="text-on-surface-variant px-4 py-6 sm:px-6">
        Loading plugins…
      </p>
    );
  if (error) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="error" title="Failed to load plugins." />
      </div>
    );
  }

  const selected = params.get('type') ?? plugins[0]?.type ?? null;
  const needle = filter.trim().toLowerCase();

  return (
    <div className="px-4 py-6 sm:px-6">
      <PageHeader
        title="Plugins"
        description="Building blocks you can place on a workflow canvas."
      />
      <div className="flex flex-col gap-6 md:flex-row">
        <nav
          aria-label="Plugins"
          className="flex shrink-0 flex-col gap-4 md:sticky md:top-28 md:max-h-[calc(100vh-8rem)] md:w-72 md:self-start md:overflow-y-auto"
        >
          <Input
            type="search"
            aria-label="Filter plugins"
            placeholder="Filter plugins"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          {CATEGORIES.map(({ key, label }) => {
            const items = plugins.filter(
              (p) => p.category === key && p.type.toLowerCase().includes(needle)
            );
            if (items.length === 0) return null;
            return (
              <section key={key}>
                <h2 className="text-label-md text-on-surface mb-1 flex items-center gap-2">
                  <span
                    aria-hidden="true"
                    className="size-2 rounded-full"
                    style={{ background: categoryAccent(key) }}
                  />
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
                          'transition-standard text-on-surface text-body-md hover:bg-surface-container-low w-full border-l-2 border-transparent px-3 py-1.5 text-left',
                          p.type === selected &&
                            'bg-surface-container border-(--sel) font-semibold'
                        )}
                        style={
                          {
                            '--sel': categoryAccent(key),
                          } as React.CSSProperties
                        }
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
          {selected ? (
            <PluginDetail type={selected} />
          ) : (
            <p>No plugins available.</p>
          )}
        </div>
      </div>
    </div>
  );
}
