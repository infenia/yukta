// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import { usePlugin } from '@/features/plugins/hooks/usePlugins';
import { useState } from 'react';
import { Input } from '@/components/ui/input';
import type { DraftEdge, DraftNode } from '../types';
import { ConfigForm, JsonField } from './ConfigForm';
import { StateMessage } from '@/components/common/StateMessage';
import { ENGINE_FIELDS } from './NodeInspector.helpers';

type Config = Record<string, unknown>;

type Tab = 'config' | 'connections' | 'policy' | 'json';
const TABS: [Tab, string][] = [
  ['config', 'Config'],
  ['connections', 'Connections'],
  ['policy', 'Policy'],
  ['json', 'JSON'],
];

/** One edge in the Connections list: the other node, the port, and a way to remove it. */
function EdgeRow({
  edge,
  other,
  onRemove,
}: {
  edge: DraftEdge;
  other: string;
  onRemove: () => void;
}) {
  return (
    <li className="flex items-center gap-2">
      <span className="min-w-0 flex-1 truncate text-code-sm">{other}</span>
      <span className="text-body-sm text-muted-foreground">
        {edge.sourcePort ?? 'default'}
      </span>
      <button
        type="button"
        aria-label={`Remove the edge ${edge.source} → ${edge.target}`}
        onClick={onRemove}
        className="text-on-surface-variant hover:text-error"
      >
        ×
      </button>
    </li>
  );
}

/**
 * Edit one node, in tabs: its plugin's config form (or a JSON editor), its edges, the engine
 * policy, and the whole config as JSON.
 */
export function NodeInspector({
  node,
  onChange,
  onRemove,
  onGroupChange,
  edges = [],
  onRemoveEdge,
  onAddEdge,
}: {
  node: DraftNode;
  onChange: (config: Config) => void;
  onRemove: () => void;
  /** A blank stage removes the declared group. */
  onGroupChange: (group: string | null) => void;
  /** The workflow's edges; Connections lists the ones that touch this node. */
  edges?: DraftEdge[];
  onRemoveEdge?: (edge: DraftEdge) => void;
  /** Start a new edge from this node (in the Edges tab, with its checks). */
  onAddEdge?: () => void;
}) {
  const [tab, setTab] = useState<Tab>('config');
  const plugin = usePlugin(node.type);
  const { engine, ...pluginConfig } = node.config;
  const engineValues = (engine ?? {}) as Config;

  const join = (nextPlugin: Config, nextEngine: Config): Config =>
    Object.keys(nextEngine).length > 0
      ? { ...nextPlugin, engine: nextEngine }
      : nextPlugin;

  const schema = plugin.data?.configSchema ?? [];

  return (
    <section className="flex flex-col gap-4" aria-label={`Node ${node.nodeId}`}>
      <header className="flex items-center gap-2">
        <h2 className="text-headline-md text-on-surface">{node.nodeId}</h2>
        <span className="text-code-sm text-on-surface-variant">
          {node.type}
        </span>
        <button
          type="button"
          onClick={onRemove}
          className="ml-auto text-label-sm font-semibold text-error"
        >
          Remove node
        </button>
      </header>

      <div
        role="tablist"
        aria-label="Node"
        className="flex gap-4 border-b border-border"
      >
        {TABS.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`-mb-px border-b-2 py-2 text-label-md ${
              tab === key
                ? 'border-primary text-on-surface'
                : 'border-transparent text-on-surface-variant hover:text-on-surface'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'config' && (
        <>
          <div className="flex flex-col gap-1">
            <label
              htmlFor="node-group"
              className="text-label-sm font-semibold text-on-surface"
            >
              Stage
            </label>
            <Input
              id="node-group"
              value={node.group ?? ''}
              placeholder={node.nodeId.split('.')[0]}
              aria-describedby="node-group-hint"
              onChange={(event) => onGroupChange(event.target.value || null)}
            />
            <span
              id="node-group-hint"
              className="text-body-sm text-on-surface-variant"
            >
              Groups nodes on the canvas. Blank uses the node-ID prefix.
            </span>
          </div>
          {plugin.isPending ? (
            <StateMessage tone="loading" title="Loading…" compact />
          ) : schema.length > 0 ? (
            <ConfigForm
              schema={schema}
              values={pluginConfig}
              onChange={(next) => onChange(join(next, engineValues))}
            />
          ) : (
            <div className="flex flex-col gap-1">
              <label
                htmlFor="cfg-json"
                className="text-label-sm font-semibold text-on-surface"
              >
                Configuration (JSON)
              </label>
              <JsonField
                key={node.nodeId}
                id="cfg-json"
                value={
                  Object.keys(pluginConfig).length > 0
                    ? pluginConfig
                    : undefined
                }
                onChange={(next) =>
                  onChange(join((next ?? {}) as Config, engineValues))
                }
              />
              {plugin.data?.usagePattern && (
                <details className="text-body-sm text-on-surface-variant">
                  <summary className="cursor-pointer">Usage</summary>
                  <div className="mt-1">
                    <CodeBlock code={plugin.data.usagePattern} label="usage" />
                  </div>
                </details>
              )}
            </div>
          )}
        </>
      )}

      {tab === 'connections' && (
        <div className="flex flex-col gap-4">
          {(
            [
              [
                'In',
                edges.filter((edge) => edge.target === node.nodeId),
                'source',
              ],
              [
                'Out',
                edges.filter((edge) => edge.source === node.nodeId),
                'target',
              ],
            ] as const
          ).map(([title, list, end]) => (
            <section key={title}>
              <h3 className="mb-1 text-overline">
                {title} <span className="tabular-nums">{list.length}</span>
              </h3>
              {list.length === 0 ? (
                <p className="text-body-sm text-muted-foreground">None.</p>
              ) : (
                <ul className="flex flex-col gap-1">
                  {list.map((edge) => (
                    <EdgeRow
                      key={`${edge.source}>${edge.sourcePort}>${edge.target}`}
                      edge={edge}
                      other={edge[end]}
                      onRemove={() => onRemoveEdge?.(edge)}
                    />
                  ))}
                </ul>
              )}
            </section>
          ))}
          <button
            type="button"
            onClick={onAddEdge}
            className="self-start text-label-md text-primary hover:underline"
          >
            Add an edge from {node.nodeId}
          </button>
        </div>
      )}

      {tab === 'policy' && (
        <ConfigForm
          schema={ENGINE_FIELDS}
          values={engineValues}
          onChange={(next) => onChange(join(pluginConfig, next))}
        />
      )}

      {tab === 'json' && (
        <div className="flex flex-col gap-1">
          <label
            htmlFor="node-json"
            className="text-label-sm font-semibold text-on-surface"
          >
            The node&apos;s config, with its engine policy (JSON)
          </label>
          <JsonField
            id="node-json"
            value={node.config}
            onChange={(next) => onChange((next ?? {}) as Config)}
          />
        </div>
      )}
    </section>
  );
}
