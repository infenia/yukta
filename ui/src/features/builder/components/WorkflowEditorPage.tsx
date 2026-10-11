// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import { usePlugins } from '@/features/plugins/hooks/usePlugins';
import { WorkflowCanvas } from '@/features/workflow/components/WorkflowCanvas';
import type { WorkflowGraph } from '@/features/workflow/types/workflow';
import { getSessionDraft, saveSession, validateWorkflow } from '../api';
import type {
  DraftEdge,
  DraftNode,
  DraftWorkflow,
  SessionDraft,
  ValidationResult,
} from '../types';
import { NodeInspector } from './NodeInspector';

const BUTTON = buttonVariants({ variant: 'outline' });
const SELECT =
  'h-8 border border-outline-variant bg-surface px-2 text-sm text-on-surface';
const NO_TASKS = {};

// fetchApi only throws Error subclasses
const errorText = (error: unknown) => (error as Error).message;

/** Draft nodes and edges shaped like the read-only graph so the existing canvas can preview them. */
export function toPreviewGraph(
  workflow: DraftWorkflow,
  categories: Record<string, string>,
  ports: Record<string, string[]>
): WorkflowGraph {
  return {
    workflowId: workflow.workflowId,
    description: workflow.description,
    nodes: workflow.nodes.map((node) => ({
      nodeId: node.nodeId,
      type: node.type,
      category:
        (categories[node.type] as WorkflowGraph['nodes'][number]['category']) ??
        null,
      description: null,
      uiDesign: null,
      outputPorts: ports[node.nodeId] ?? ['default'],
    })),
    edges: workflow.edges,
    topologicalOrder: [],
  };
}

function uniqueNodeId(type: string, nodes: DraftNode[]): string {
  const base = type.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  let n = 1;
  while (nodes.some((node) => node.nodeId === `${base}-${n}`)) n += 1;
  return `${base}-${n}`;
}

function Editor({
  session,
  initial,
}: {
  session: SessionDraft;
  initial: DraftWorkflow;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const plugins = usePlugins();
  const [workflow, setWorkflow] = useState(initial);
  const [selected, setSelected] = useState<string | null>(
    initial.nodes[0]?.nodeId ?? null
  );
  const [result, setResult] = useState<ValidationResult | null>(null);
  // Ports depend on node config and survive edits until the next validation refreshes them.
  const [ports, setPorts] = useState<Record<string, string[]>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [newType, setNewType] = useState('');
  const [edge, setEdge] = useState<DraftEdge>({
    source: '',
    target: '',
    sourcePort: null,
  });

  const categories = useMemo(
    () =>
      Object.fromEntries((plugins.data ?? []).map((p) => [p.type, p.category])),
    [plugins.data]
  );
  const graph = useMemo(
    () => toPreviewGraph(workflow, categories, ports),
    [workflow, categories, ports]
  );
  const node = workflow.nodes.find((n) => n.nodeId === selected);

  const update = (patch: Partial<DraftWorkflow>) => {
    setWorkflow((current) => ({ ...current, ...patch }));
    setResult(null);
  };

  const addNode = () => {
    const type = newType || plugins.data?.[0]?.type;
    if (!type) return;
    const nodeId = uniqueNodeId(type, workflow.nodes);
    update({ nodes: [...workflow.nodes, { nodeId, type, config: {} }] });
    setSelected(nodeId);
  };

  const removeNode = (nodeId: string) => {
    update({
      nodes: workflow.nodes.filter((n) => n.nodeId !== nodeId),
      edges: workflow.edges.filter(
        (e) => e.source !== nodeId && e.target !== nodeId
      ),
    });
    setSelected(null);
  };

  const addEdge = () => {
    if (!edge.source || !edge.target) return;
    update({ edges: [...workflow.edges, edge] });
    setEdge({ source: '', target: '', sourcePort: null });
  };

  const check = async () => {
    setBusy(true);
    setError(null);
    try {
      const outcome = await validateWorkflow(workflow);
      setResult(outcome);
      setPorts(outcome.outputPorts);
      return outcome;
    } catch (e) {
      setError(errorText(e));
      return null;
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    const outcome = await check();
    if (!outcome?.valid) return;
    setBusy(true);
    try {
      await saveSession({
        ...session,
        workflows: { ...session.workflows, [workflow.workflowId]: workflow },
      });
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      navigate(
        `/sessions/${encodeURIComponent(session.sessionId)}/workflow/${encodeURIComponent(workflow.workflowId)}`
      );
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-1 flex-col" data-testid="workflow-editor">
      <div className="border-outline-variant flex flex-wrap items-center gap-3 border-b px-4 py-3 sm:px-6">
        <h1 className="text-on-surface text-headline-md">
          Edit {workflow.workflowId}
        </h1>
        <Input
          aria-label="Description"
          placeholder="Description"
          value={workflow.description}
          maxLength={256}
          onChange={(event) => update({ description: event.target.value })}
          className="w-72"
        />
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            className={BUTTON}
            disabled={busy}
            onClick={check}
          >
            Validate
          </button>
          <button
            type="button"
            className={BUTTON}
            disabled={busy}
            onClick={save}
          >
            Save
          </button>
        </div>
      </div>
      <div className="px-4 py-2 sm:px-6">
        <InlineError
          message={error ?? (result && !result.valid ? result.message : null)}
        />
        {result?.valid && (
          <p className="text-success text-sm">Workflow is valid.</p>
        )}
      </div>

      <div className="flex min-h-100 flex-1">
        <aside className="border-outline-variant flex w-80 shrink-0 flex-col gap-4 overflow-y-auto border-r p-4">
          <div className="flex gap-2">
            <select
              aria-label="Plugin type"
              className={`${SELECT} min-w-0 flex-1`}
              value={newType}
              onChange={(event) => setNewType(event.target.value)}
            >
              {(['TRIGGER', 'PROCESSOR', 'TERMINAL'] as const).map(
                (category) => (
                  <optgroup key={category} label={category}>
                    {plugins.data
                      ?.filter((p) => p.category === category)
                      .map((p) => (
                        <option key={p.type} value={p.type}>
                          {p.type}
                        </option>
                      ))}
                  </optgroup>
                )
              )}
            </select>
            <button type="button" className={BUTTON} onClick={addNode}>
              Add node
            </button>
          </div>

          <ul className="flex flex-col" aria-label="Nodes">
            {workflow.nodes.map((n) => (
              <li key={n.nodeId}>
                <button
                  type="button"
                  aria-current={n.nodeId === selected}
                  onClick={() => setSelected(n.nodeId)}
                  className={`flex w-full items-baseline gap-2 border-l-2 px-3 py-2 text-left text-sm ${
                    n.nodeId === selected
                      ? 'border-primary bg-surface-container-low font-semibold'
                      : 'border-outline-variant'
                  }`}
                >
                  {n.nodeId}
                  <span className="text-on-surface-variant font-mono text-xs">
                    {n.type}
                  </span>
                </button>
              </li>
            ))}
          </ul>

          <div className="flex flex-col gap-2">
            <h2 className="text-on-surface text-xs font-semibold tracking-wider">
              EDGES
            </h2>
            <ul aria-label="Edges" className="flex flex-col gap-1 text-sm">
              {workflow.edges.map((e, index) => (
                <li
                  key={`${e.source}-${e.target}-${e.sourcePort}`}
                  className="flex items-center"
                >
                  <span className="font-mono text-xs">
                    {e.source}
                    {e.sourcePort ? `:${e.sourcePort}` : ''} → {e.target}
                  </span>
                  <button
                    type="button"
                    aria-label={`Remove edge ${e.source} to ${e.target}`}
                    className="text-error ml-auto text-xs"
                    onClick={() =>
                      update({
                        edges: workflow.edges.filter((_, i) => i !== index),
                      })
                    }
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap gap-1">
              <select
                aria-label="Edge source"
                className={SELECT}
                value={edge.source}
                onChange={(event) =>
                  setEdge({
                    ...edge,
                    source: event.target.value,
                    sourcePort: null,
                  })
                }
              >
                <option value="">from…</option>
                {workflow.nodes.map((n) => (
                  <option key={n.nodeId}>{n.nodeId}</option>
                ))}
              </select>
              <select
                aria-label="Edge port"
                className={SELECT}
                value={edge.sourcePort ?? ''}
                onChange={(event) =>
                  setEdge({ ...edge, sourcePort: event.target.value || null })
                }
              >
                <option value="">all ports</option>
                {(ports[edge.source] ?? []).map((port) => (
                  <option key={port}>{port}</option>
                ))}
              </select>
              <select
                aria-label="Edge target"
                className={SELECT}
                value={edge.target}
                onChange={(event) =>
                  setEdge({ ...edge, target: event.target.value })
                }
              >
                <option value="">to…</option>
                {workflow.nodes.map((n) => (
                  <option key={n.nodeId}>{n.nodeId}</option>
                ))}
              </select>
              <button type="button" className={BUTTON} onClick={addEdge}>
                Add edge
              </button>
            </div>
            <p className="text-on-surface-variant text-xs">
              Validate to list a node&apos;s ports for its configuration.
            </p>
          </div>
        </aside>

        <div className="min-w-0 flex-1">
          <WorkflowCanvas
            key={workflow.nodes.length}
            graph={graph}
            tasksByNode={NO_TASKS}
          />
        </div>

        <aside className="border-outline-variant w-96 shrink-0 overflow-y-auto border-l p-4">
          {node ? (
            <NodeInspector
              key={node.nodeId}
              node={node}
              onRemove={() => removeNode(node.nodeId)}
              onChange={(config) =>
                update({
                  nodes: workflow.nodes.map((n) =>
                    n.nodeId === node.nodeId ? { ...n, config } : n
                  ),
                })
              }
            />
          ) : (
            <p className="text-on-surface-variant">
              Select a node to configure it.
            </p>
          )}
        </aside>
      </div>
    </div>
  );
}

/** Form-based workflow editor with a live canvas preview; saves the whole session. */
export function WorkflowEditorPage() {
  const { sessionId = '', workflowId = '' } = useParams<{
    sessionId: string;
    workflowId: string;
  }>();
  const session = useQuery({
    queryKey: ['sessions', sessionId, 'config'],
    queryFn: () => getSessionDraft(sessionId),
    staleTime: 0,
  });

  if (session.isPending) {
    return <p className="text-on-surface-variant p-6">Loading…</p>;
  }
  if (session.error) {
    return (
      <p role="alert" className="text-error p-6">
        Failed to load session
      </p>
    );
  }
  const initial = session.data.workflows[workflowId] ?? {
    workflowId,
    description: '',
    nodes: [],
    edges: [],
  };
  return <Editor session={session.data} initial={initial} />;
}
