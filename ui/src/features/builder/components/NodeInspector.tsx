// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import type { ConfigField } from '@/features/plugins/types';
import { usePlugin } from '@/features/plugins/hooks/usePlugins';
import type { DraftNode } from '../types';
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

/** Edit one node: its plugin's config form (or a JSON editor) and the engine policy. */
export function NodeInspector({
  node,
  onChange,
  onRemove,
}: {
  node: DraftNode;
  onChange: (config: Config) => void;
  onRemove: () => void;
}) {
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
              Object.keys(pluginConfig).length > 0 ? pluginConfig : undefined
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

      <details>
        <summary className="cursor-pointer text-label-md text-on-surface">
          Error handling &amp; retries
        </summary>
        <div className="mt-3">
          <ConfigForm
            schema={ENGINE_FIELDS}
            values={engineValues}
            onChange={(next) => onChange(join(pluginConfig, next))}
          />
        </div>
      </details>
    </section>
  );
}
