// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import type { ConfigField } from '@/features/plugins/types';
import { usePlugin } from '@/features/plugins/hooks/usePlugins';
import { useState } from 'react';
import type { DraftEdge, DraftNode } from '../types';
import { ConfigForm, JsonField } from './ConfigForm';
import { StateMessage } from '@/components/common/StateMessage';

/** Engine-level failure policy, available on every node (core `NodePolicy`). */
export const ENGINE_FIELDS: ConfigField[] = [
  {
    name: 'onError',
    type: 'ENUM',
    required: false,
    defaultValue: 'FAIL',
    options: ['FAIL', 'CONTINUE', 'ROUTE'],
    description:
      'What a failing message does (processors). ROUTE sends it to the error port.',
  },
  {
    name: 'trigger',
    type: 'ENUM',
    required: false,
    defaultValue: 'ALL_SUCCESS',
    options: ['ALL_SUCCESS', 'ALL_DONE', 'ONE_SUCCESS'],
    description: 'When a node with several parents starts.',
  },
  {
    name: 'retry',
    type: 'MAP',
    required: false,
    defaultValue: null,
    options: [],
    description:
      '{"maxAttempts": 3, "backoffMs": 200, "maxBackoffMs": 5000} (processors).',
  },
  {
    name: 'elementTimeoutMs',
    type: 'NUMBER',
    required: false,
    defaultValue: null,
    options: [],
    description: 'Longest a processor may stay silent for one message.',
  },
  {
    name: 'deadlineMs',
    type: 'NUMBER',
    required: false,
    defaultValue: null,
    options: [],
    description: 'Longest the node may run in total.',
  },
  {
    name: 'failureBudget',
    type: 'MAP',
    required: false,
    defaultValue: null,
    options: [],
    description:
      '{"maxFailures": 10, "windowMs": 60000}; needs onError CONTINUE or ROUTE.',
  },
];

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
      <span className="text-code-sm min-w-0 flex-1 truncate">{other}</span>
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
  edges = [],
  onRemoveEdge,
  onAddEdge,
}: {
  node: DraftNode;
  onChange: (config: Config) => void;
  onRemove: () => void;
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
        <h2 className="text-on-surface text-headline-md">{node.nodeId}</h2>
        <span className="text-on-surface-variant text-code-sm">
          {node.type}
        </span>
        <button
          type="button"
          onClick={onRemove}
          className="text-error text-label-sm ml-auto font-semibold"
        >
          Remove node
        </button>
      </header>

      <div
        role="tablist"
        aria-label="Node"
        className="border-border flex gap-4 border-b"
      >
        {TABS.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`text-label-md -mb-px border-b-2 py-2 ${
              tab === key
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant hover:text-on-surface border-transparent'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'config' && (
        <>
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
                className="text-on-surface text-label-sm font-semibold"
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
                <details className="text-on-surface-variant text-body-sm">
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
              <h3 className="text-overline mb-1">
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
            className="text-primary text-label-md self-start hover:underline"
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
            className="text-on-surface text-label-sm font-semibold"
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
