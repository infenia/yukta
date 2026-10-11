// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Input } from '@/components/ui/input';
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
    <article className="flex flex-col gap-4">
      <header>
        <h2 className="text-headline-md text-on-surface">{plugin.type}</h2>
        <span className="text-on-surface-variant text-xs tracking-wide uppercase">
          {plugin.category}
        </span>
      </header>
      {plugin.description && (
        <p className="text-on-surface">{plugin.description}</p>
      )}
      <section>
        <h3 className="text-on-surface mb-1 text-sm font-semibold">
          Output ports
        </h3>
        <p className="text-on-surface-variant text-sm">{ports}</p>
      </section>
      {plugin.configSchema && plugin.configSchema.length > 0 && (
        <section>
          <h3 className="text-on-surface mb-1 text-sm font-semibold">
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
          <h3 className="text-on-surface mb-1 text-sm font-semibold">Usage</h3>
          <CodeBlock code={plugin.usagePattern} label="usage" />
        </section>
      )}
      <section>
        <h3 className="text-on-surface mb-1 text-sm font-semibold">
          On the canvas
        </h3>
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
    return <p className="text-on-surface-variant p-6">Loading plugins…</p>;
  if (error) {
    return (
      <div className="p-6">
        <StateMessage tone="error" title="Failed to load plugins." />
      </div>
    );
  }

  const selected = params.get('type') ?? plugins[0]?.type ?? null;
  const needle = filter.trim().toLowerCase();

  return (
    <div className="flex flex-col gap-6 p-6 md:flex-row">
      <nav
        aria-label="Plugins"
        className="flex shrink-0 flex-col gap-4 md:w-72"
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
              <h2 className="text-on-surface-variant mb-1 text-xs font-semibold tracking-wide uppercase">
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
                        'text-on-surface hover:bg-surface-container-low w-full px-3 py-1.5 text-left text-sm',
                        p.type === selected &&
                          'bg-surface-container font-semibold'
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
        {selected ? (
          <PluginDetail type={selected} />
        ) : (
          <p>No plugins available.</p>
        )}
      </div>
    </div>
  );
}
