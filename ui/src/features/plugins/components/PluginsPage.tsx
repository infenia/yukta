// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { PageHeader } from '@/components/layout/PageHeader';
import { categoryAccent } from '@/features/workflow/lib/categoryAccent';
import { pluginLabel } from '@/features/workflow/lib/pluginIcons';
import type { PluginCategory } from '@/features/workflow/types/workflow';
import { useCopyCommand } from '@/features/workflow/hooks/useCopyCommand';
import { usePlugin, usePlugins } from '../hooks/usePlugins';
import { nodeExample } from '../lib/nodeExample';
import { getPluginUsage } from '../api/plugins';
import { PluginCanvasPreview } from './PluginCanvasPreview';

const CATEGORIES: { key: PluginCategory; label: string }[] = [
  { key: 'TRIGGER', label: 'Triggers' },
  { key: 'PROCESSOR', label: 'Processors' },
  { key: 'TERMINAL', label: 'Terminals' },
];

type Tab = 'config' | 'ports' | 'example' | 'usage';

/** A default as the config table shows it. */
const defaultText = (value: unknown) =>
  value == null
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);

function PluginDetail({ type }: { type: string }) {
  const { data: plugin, isPending, error } = usePlugin(type);
  const [chosen, setChosen] = useState<Tab | null>(null);
  const { status: copied, copy } = useCopyCommand();
  const usage = useQuery({
    queryKey: ['plugins', type, 'usage'],
    queryFn: () => getPluginUsage(type),
  });
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !plugin) {
    return <StateMessage tone="error" title={`Failed to load ${type}.`} />;
  }

  const ports =
    plugin.outputPorts.length > 0
      ? plugin.outputPorts.join(', ')
      : plugin.category === 'TERMINAL'
        ? 'None (terminal)'
        : 'default';
  const schema = plugin.configSchema ?? [];
  const example = JSON.stringify(nodeExample(plugin), null, 2);
  const tabs: { key: Tab; label: string; count?: number }[] = [
    ...(schema.length > 0
      ? [
          {
            key: 'config' as const,
            label: 'Configuration',
            count: schema.length,
          },
        ]
      : []),
    { key: 'ports', label: 'Ports', count: plugin.outputPorts.length },
    { key: 'example', label: 'Example' },
    { key: 'usage', label: 'Used in', count: usage.data?.length },
  ];
  const tab = chosen ?? tabs[0].key;

  return (
    <article className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <div className="flex min-w-0 flex-col gap-5">
        <header>
          <h2 className="text-headline-lg text-on-surface">
            {pluginLabel(plugin.type)}
          </h2>
          <div className="text-body-md text-on-surface-variant mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="inline-flex items-center gap-2">
              <span
                aria-hidden="true"
                className="size-2 rounded-full"
                style={{ background: categoryAccent(plugin.category) }}
              />
              {pluginLabel(plugin.category)}
            </span>
            {/* the ID is what a workflow's JSON, the CLI and MCP calls use */}
            <code className="text-code-sm select-all">{plugin.type}</code>
          </div>
        </header>
        {plugin.description && (
          <p className="text-body-md text-on-surface max-w-prose">
            {plugin.description}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => copy(example)}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              data_object
            </span>
            Copy node JSON
          </Button>
          {copied && (
            <span
              role="status"
              className="text-label-sm text-on-surface-variant"
            >
              {copied === 'copied' ? 'Copied' : 'Copy failed'}
            </span>
          )}
        </div>
        <div role="tablist" className="border-border flex gap-5 border-b">
          {tabs.map(({ key, label, count }) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              onClick={() => setChosen(key)}
              className={cn(
                'text-label-md -mb-px border-b-2 py-2',
                tab === key
                  ? 'border-primary text-on-surface'
                  : 'text-on-surface-variant hover:text-on-surface border-transparent'
              )}
            >
              {label}
              {count !== undefined && (
                <span className="text-body-sm text-muted-foreground ml-1.5 tabular-nums">
                  {count}
                </span>
              )}
            </button>
          ))}
        </div>
        {tab === 'ports' && (
          <p className="text-body-md text-on-surface-variant">{ports}</p>
        )}
        {tab === 'config' && (
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Field</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Default</TableHead>
                  <TableHead>What it does</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {schema.map((field) => (
                  <TableRow key={field.name} className="align-top">
                    <TableCell className="text-code-sm">
                      {field.name}
                      {field.required && <span className="text-error"> *</span>}
                    </TableCell>
                    <TableCell className="text-body-sm text-on-surface-variant">
                      {field.type}
                    </TableCell>
                    <TableCell className="text-code-sm text-on-surface-variant">
                      {defaultText(field.defaultValue)}
                    </TableCell>
                    <TableCell className="text-body-sm text-on-surface-variant whitespace-normal">
                      {field.description}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        )}
        {tab === 'example' && (
          <>
            <CodeBlock code={example} label="example" />
            {plugin.usagePattern && (
              <section>
                <h3 className="text-label-md text-on-surface mb-1.5">Usage</h3>
                <CodeBlock code={plugin.usagePattern} label="usage" />
              </section>
            )}
          </>
        )}
        {tab === 'usage' &&
          (usage.error ? (
            <StateMessage
              tone="error"
              title="Failed to load where it is used."
              compact
            />
          ) : usage.isPending ? (
            <StateMessage tone="loading" title="Loading…" compact />
          ) : usage.data.length === 0 ? (
            <p className="text-body-md text-on-surface-variant">
              No workflow uses {plugin.type} yet.
            </p>
          ) : (
            <ul className="flex flex-col gap-1">
              {usage.data.map((use) => (
                <li
                  key={`${use.sessionId}/${use.workflowId}`}
                  className="flex items-baseline justify-between gap-3"
                >
                  <Link
                    className="text-body-md text-primary"
                    to={`/sessions/${encodeURIComponent(use.sessionId)}/workflow/${encodeURIComponent(use.workflowId)}`}
                  >
                    {use.sessionId} / {use.workflowId}
                  </Link>
                  <span className="text-body-sm text-muted-foreground tabular-nums">
                    {use.nodes} {use.nodes === 1 ? 'node' : 'nodes'}
                  </span>
                </li>
              ))}
            </ul>
          ))}
      </div>
      <section className="min-w-0 xl:sticky xl:top-18 xl:self-start">
        <h3 className="text-label-md text-on-surface mb-1.5">On the canvas</h3>
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
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="loading" title="Loading plugins…" />
      </div>
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
          className="flex shrink-0 flex-col gap-4 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-72 md:self-start md:overflow-y-auto"
        >
          <Input
            type="search"
            aria-label="Filter plugins"
            placeholder="Filter by name or ID"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          {CATEGORIES.map(({ key, label }) => {
            const items = plugins.filter(
              (p) =>
                p.category === key &&
                `${p.type} ${pluginLabel(p.type)}`
                  .toLowerCase()
                  .includes(needle)
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
                  <span className="text-body-sm text-muted-foreground tabular-nums">
                    {items.length}
                  </span>
                </h2>
                <ul className="flex flex-col gap-0.5">
                  {items.map((p) => (
                    <li key={p.type}>
                      <button
                        type="button"
                        aria-current={p.type === selected}
                        onClick={() => setParams({ type: p.type })}
                        className={cn(
                          'text-body-md transition-standard w-full rounded-lg px-3 py-1.5 text-left',
                          p.type === selected
                            ? 'bg-accent text-accent-foreground font-semibold'
                            : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
                        )}
                      >
                        <span className="block">{pluginLabel(p.type)}</span>
                        <span className="text-code-sm block text-[11px] opacity-75">
                          {p.type}
                        </span>
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
            <PluginDetail key={selected} type={selected} />
          ) : (
            <StateMessage title="No plugins available." icon="extension" />
          )}
        </div>
      </div>
    </div>
  );
}
