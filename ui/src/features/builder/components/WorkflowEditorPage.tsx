// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
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
import { JsonValidityContext } from './ConfigForm';
import { NodeInspector } from './NodeInspector';
import { StateMessage } from '@/components/common/StateMessage';
import { Callout } from '@/components/common/Callout';
import { NativeSelect } from '@/components/ui/native-select';

const BUTTON = buttonVariants({ variant: 'outline' });
const PLUGIN_CATEGORIES = ['TRIGGER', 'PROCESSOR', 'TERMINAL'] as const;
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
  // JSON fields whose text does not parse: what they show is not what would be saved.
  const [invalidJson, setInvalidJson] = useState<ReadonlySet<string>>(
    new Set()
  );
  const reportJson = useCallback((key: string, invalid: boolean) => {
    setInvalidJson((current) => {
      if (current.has(key) === invalid) return current;
      const next = new Set(current);
      if (invalid) next.add(key);
      else next.delete(key);
      return next;
    });
  }, []);
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
  // The select lists plugins by category; until the user picks one, what it shows is the first option.
  const selectedType =
    newType ||
    PLUGIN_CATEGORIES.flatMap((c) =>
      (plugins.data ?? []).filter((p) => p.category === c)
    )[0]?.type ||
    '';

  const update = (patch: Partial<DraftWorkflow>) => {
    setWorkflow((current) => ({ ...current, ...patch }));
    setResult(null);
  };

  const addNode = () => {
    const type = selectedType;
    if (!type) return;
    const nodeId = uniqueNodeId(type, workflow.nodes);
    update({ nodes: [...workflow.nodes, { nodeId, type, config: {} }] });
    setSelected(nodeId);
  };

  const removeNode = (nodeId: string) => {
    setPorts((current) =>
      Object.fromEntries(
        Object.entries(current).filter(([id]) => id !== nodeId)
      )
    );
    setEdge((draft) =>
      draft.source === nodeId || draft.target === nodeId
        ? { source: '', target: '', sourcePort: null }
        : draft
    );
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
    if (edge.source === edge.target) {
      setError('An edge cannot connect a node to itself.');
      return;
    }
    if (
      workflow.edges.some(
        (e) =>
          e.source === edge.source &&
          e.target === edge.target &&
          (e.sourcePort ?? null) === edge.sourcePort
      )
    ) {
      setError('That edge already exists.');
      return;
    }
    setError(null);
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
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3 sm:px-6">
        <h1 className="text-headline-lg text-on-surface">
          Edit {workflow.workflowId}
        </h1>
        <Input
          aria-label="Description"
          placeholder="Description"
          value={workflow.description}
          maxLength={256}
          onChange={(event) => update({ description: event.target.value })}
          className="w-full sm:w-72"
        />
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            className={BUTTON}
            disabled={busy || invalidJson.size > 0}
            onClick={check}
          >
            Validate
          </button>
          <button
            type="button"
            className={BUTTON}
            disabled={busy || invalidJson.size > 0}
            onClick={save}
          >
            Save
          </button>
        </div>
      </div>
      <div className="px-4 py-2 sm:px-6">
        <InlineError
          message={
            error ??
            (invalidJson.size > 0
              ? 'Fix the JSON field that does not parse before validating or saving.'
              : result && !result.valid
                ? result.message
                : null)
          }
        />
        {result?.valid && (
          <Callout kind="success" compact>
            Workflow is valid.
          </Callout>
        )}
      </div>

      {/* Three columns from lg up; on phones and tablets the panels stack (list, canvas,
          inspector) so each one gets the full width */}
      <div className="flex min-h-100 flex-1 flex-col lg:flex-row">
        <aside
          aria-label="Nodes and edges"
          className="flex w-full shrink-0 flex-col gap-4 border-b border-border p-4 lg:w-80 lg:overflow-y-auto lg:border-r lg:border-b-0"
        >
          <div className="flex gap-2">
            <NativeSelect
              aria-label="Plugin type"
              className="min-w-0 flex-1"
              value={selectedType}
              onChange={(event) => setNewType(event.target.value)}
            >
              {PLUGIN_CATEGORIES.map((category) => (
                <optgroup key={category} label={category}>
                  {plugins.data
                    ?.filter((p) => p.category === category)
                    .map((p) => (
                      <option key={p.type} value={p.type}>
                        {p.type}
                      </option>
                    ))}
                </optgroup>
              ))}
            </NativeSelect>
            <button type="button" className={BUTTON} onClick={addNode}>
              Add node
            </button>
          </div>

          <ul className="flex flex-col gap-0.5" aria-label="Nodes">
            {workflow.nodes.map((n) => (
              <li key={n.nodeId}>
                <button
                  type="button"
                  aria-current={n.nodeId === selected}
                  onClick={() => setSelected(n.nodeId)}
                  className={`flex w-full items-baseline gap-2 rounded-lg px-3 py-2 text-left text-body-md transition-standard ${
                    n.nodeId === selected
                      ? 'bg-accent font-semibold text-accent-foreground'
                      : 'text-on-surface hover:bg-muted'
                  }`}
                >
                  {n.nodeId}
                  <span className="text-code-sm text-on-surface-variant">
                    {n.type}
                  </span>
                </button>
              </li>
            ))}
          </ul>

          <div className="flex flex-col gap-2">
            <h2 className="text-overline">Edges</h2>
            <ul aria-label="Edges" className="flex flex-col gap-1 text-body-md">
              {workflow.edges.map((e, index) => (
                <li
                  key={`${e.source}-${e.target}-${e.sourcePort}`}
                  className="flex items-center"
                >
                  <span className="text-code-sm">
                    {e.source}
                    {e.sourcePort ? `:${e.sourcePort}` : ''} → {e.target}
                  </span>
                  <button
                    type="button"
                    aria-label={`Remove edge ${e.source} to ${e.target}`}
                    className="ml-auto text-body-sm text-error"
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
              <NativeSelect
                aria-label="Edge source"
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
              </NativeSelect>
              <NativeSelect
                aria-label="Edge port"
                value={edge.sourcePort ?? ''}
                onChange={(event) =>
                  setEdge({ ...edge, sourcePort: event.target.value || null })
                }
              >
                <option value="">all ports</option>
                {(ports[edge.source] ?? []).map((port) => (
                  <option key={port}>{port}</option>
                ))}
              </NativeSelect>
              <NativeSelect
                aria-label="Edge target"
                value={edge.target}
                onChange={(event) =>
                  setEdge({ ...edge, target: event.target.value })
                }
              >
                <option value="">to…</option>
                {workflow.nodes.map((n) => (
                  <option key={n.nodeId}>{n.nodeId}</option>
                ))}
              </NativeSelect>
              <button type="button" className={BUTTON} onClick={addEdge}>
                Add edge
              </button>
            </div>
            <p className="text-body-sm text-on-surface-variant">
              Validate to list a node&apos;s ports for its configuration.
            </p>
          </div>
        </aside>

        <div
          data-testid="editor-canvas"
          className="h-80 min-w-0 shrink-0 lg:h-auto lg:flex-1"
        >
          <WorkflowCanvas
            key={workflow.nodes.length}
            graph={graph}
            tasksByNode={NO_TASKS}
          />
        </div>

        <aside
          aria-label="Node inspector"
          className="w-full shrink-0 border-t border-border p-4 lg:w-96 lg:overflow-y-auto lg:border-t-0 lg:border-l"
        >
          {node ? (
            <JsonValidityContext.Provider value={reportJson}>
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
            </JsonValidityContext.Provider>
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
  // The editor seeds its draft once, and the cache may hold an older definition (after a rollback, or
  // a save from another tab). So wait for the fetch this mount starts; later refetches keep the draft.
  const [openedAt] = useState(Date.now);

  if (
    session.isPending ||
    (session.isSuccess && session.dataUpdatedAt < openedAt)
  ) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="loading" title="Loading…" />
      </div>
    );
  }
  if (session.error) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="error" title="Failed to load session" />
      </div>
    );
  }
  const initial = session.data.workflows[workflowId] ?? {
    workflowId,
    description: '',
    nodes: [],
    edges: [],
  };
  // Keyed by route so moving to another workflow starts a fresh draft instead of keeping the old one.
  return (
    <Editor
      key={`${sessionId}/${workflowId}`}
      session={session.data}
      initial={initial}
    />
  );
}
