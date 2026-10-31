// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import BreadcrumbNav from '@/components/layout/BreadcrumbNav';
import { RequirePermission } from '@/components/common/RequirePermission';
import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { LeaveGuard } from './LeaveGuard';
import { InlineError } from '@/components/common/InlineError';
import { usePlugins } from '@/features/plugins/hooks/usePlugins';
import { WorkflowCanvas } from '@/features/workflow/components/WorkflowCanvas';
import { GroupingSwitch } from '@/features/workflow/components/nodes/StageNodes';
import { groupNodes, stageOf } from '@/features/workflow/lib/groups';
import { getSessionDraft, saveSession, validateWorkflow } from '../api';
import type {
  DraftEdge,
  DraftNode,
  DraftWorkflow,
  SessionDraft,
  ValidationResult,
} from '../types';
import { JsonValidityContext } from './ConfigForm.helpers';
import { NodeInspector } from './NodeInspector';
import { StateMessage } from '@/components/common/StateMessage';
import { Callout } from '@/components/common/Callout';
import { NativeSelect } from '@/components/ui/native-select';
import { toPreviewGraph } from './WorkflowEditorPage.helpers';

const BUTTON = buttonVariants({ variant: 'outline' });
const PLUGIN_CATEGORIES = ['TRIGGER', 'PROCESSOR', 'TERMINAL'] as const;
const NO_TASKS = {};

// fetchApi only throws Error subclasses
const errorText = (error: unknown) => (error as Error).message;

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
  const firstNode = initial.nodes[0]?.nodeId ?? null;
  const [selected, setSelected] = useState<string | null>(firstNode);
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
  const [listTab, setListTab] = useState<'nodes' | 'edges'>('nodes');
  const [nodeQuery, setNodeQuery] = useState('');
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

  // Stages on the canvas: the selected node's stage is always open; closing it deselects
  const stages = useMemo(() => groupNodes(workflow.nodes), [workflow.nodes]);
  /** Where each stage's first node is, so the outline lists a stage's nodes together. */
  const stageRank = useMemo(() => {
    const rank = new Map<string, number>();
    workflow.nodes.forEach((n, index) => {
      if (!rank.has(stageOf(n))) rank.set(stageOf(n), index);
    });
    return rank;
  }, [workflow.nodes]);
  const [grouped, setGrouped] = useState(true);
  const [openStages, setOpenStages] = useState<ReadonlySet<string>>(new Set());
  const selectedStage =
    node && stages.has(stageOf(node)) ? stageOf(node) : null;
  const openNow = new Set(openStages);
  if (selectedStage) openNow.add(selectedStage);
  const toggleStage = (stage: string) => {
    const next = new Set(openNow);
    if (next.delete(stage)) {
      if (stage === selectedStage) setSelected(null);
    } else {
      next.add(stage);
    }
    setOpenStages(next);
  };
  // ponytail: a new object each render, so the canvas lays out again on every edit; memoize if
  // large drafts get slow (react-hooks' compiler lint rejects the obvious useMemo here)
  const stageView =
    grouped && stages.size > 0
      ? { open: openNow, onToggle: toggleStage }
      : undefined;
  // The select lists plugins by category; until the user picks one, what it shows is the first option.
  const selectedType =
    newType ||
    PLUGIN_CATEGORIES.flatMap((c) =>
      (plugins.data ?? []).filter((p) => p.category === c)
    )[0]?.type ||
    '';

  // Unsaved: the draft differs from what was loaded (saving leaves the page)
  const dirty = JSON.stringify(workflow) !== JSON.stringify(initial);
  // Bumped by Discard, so the inspector's fields drop the text they hold
  const [revision, setRevision] = useState(0);
  const discard = () => {
    setWorkflow(initial);
    setSelected(firstNode);
    setResult(null);
    setError(null);
    setInvalidJson(new Set());
    setRevision((n) => n + 1);
  };
  useEffect(() => {
    if (!dirty) return undefined;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  // Saving navigates away on purpose; that must not ask
  const leaving = useRef(false);

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

  /** Adds an edge from the Edges form, or one drawn on the canvas, unless it is a loop or a copy. */
  const addEdge = (next: DraftEdge = edge) => {
    if (!next.source || !next.target) return;
    if (next.source === next.target) {
      setError('An edge cannot connect a node to itself.');
      return;
    }
    if (
      workflow.edges.some(
        (e) =>
          e.source === next.source &&
          e.target === next.target &&
          (e.sourcePort ?? null) === next.sourcePort
      )
    ) {
      setError('That edge already exists.');
      return;
    }
    setError(null);
    update({ edges: [...workflow.edges, next] });
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
      // The server replaces the whole session, so write into its current copy, not the one loaded
      // when the editor opened: other workflows saved since stay as they are. This workflow
      // changing underneath us is refused. ponytail: a check-then-write, so two saves landing in the
      // same instant can still race; a version the server compares on save would close that.
      const current = await getSessionDraft(session.sessionId);
      const stored = current.workflows[workflow.workflowId] ?? null;
      const loaded = session.workflows[workflow.workflowId] ? initial : null;
      if (JSON.stringify(stored) !== JSON.stringify(loaded)) {
        setError(
          'Someone else saved this workflow after you opened it. Reload the page to see their version; saving now would overwrite it.'
        );
        setBusy(false);
        return;
      }
      await saveSession({
        ...current,
        workflows: { ...current.workflows, [workflow.workflowId]: workflow },
      });
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      leaving.current = true;
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
      <LeaveGuard dirty={dirty} leaving={leaving} />
      <div className="border-border flex flex-wrap items-center gap-3 border-b px-4 py-3 sm:px-6">
        <BreadcrumbNav className="basis-full" />
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
          {dirty && (
            <>
              <span
                role="status"
                className="text-label-sm text-on-surface-variant inline-flex items-center gap-1.5"
              >
                <span
                  aria-hidden="true"
                  className="bg-warning size-2 rounded-full"
                />
                Unsaved changes
              </span>
              <button
                type="button"
                className={buttonVariants({ variant: 'ghost' })}
                disabled={busy}
                onClick={discard}
              >
                Discard
              </button>
            </>
          )}
          <button
            type="button"
            className={BUTTON}
            disabled={busy || invalidJson.size > 0}
            onClick={check}
          >
            Validate
          </button>
          <RequirePermission perm="editSessions">
            <button
              type="button"
              className={buttonVariants()}
              disabled={busy || invalidJson.size > 0}
              onClick={save}
            >
              Save version
            </button>
          </RequirePermission>
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
        {result?.warnings?.map((warning) => (
          <Callout
            key={`${warning.nodeId}/${warning.field}`}
            kind="warning"
            compact
            className="mt-2"
          >
            <span className="flex flex-wrap items-center gap-2">
              {warning.message}
              <button
                type="button"
                className="text-primary font-semibold hover:underline"
                onClick={() => {
                  // the warnings are about ports so far: start an edge from it
                  setSelected(warning.nodeId);
                  setListTab('edges');
                  setEdge({
                    source: warning.nodeId,
                    target: '',
                    sourcePort: warning.field,
                  });
                  requestAnimationFrame(() =>
                    document.getElementById('edge-target')?.focus()
                  );
                }}
              >
                Fix it
              </button>
            </span>
          </Callout>
        ))}
      </div>

      {/* Three columns from lg up; on phones and tablets the panels stack (list, canvas,
          inspector) so each one gets the full width */}
      {/* From lg up the three panels share a fixed-height workspace and each scrolls on its own,
          so a 100-node workflow no longer stretches the page */}
      <div className="flex flex-col lg:h-[clamp(28rem,calc(100dvh-14rem),56rem)] lg:flex-row">
        <aside
          aria-label="Nodes and edges"
          className="border-border flex w-full shrink-0 flex-col gap-4 border-b p-4 lg:w-80 lg:overflow-y-auto lg:border-r lg:border-b-0"
        >
          <div role="tablist" aria-label="Outline" className="flex gap-1">
            {(
              [
                ['nodes', 'Nodes', workflow.nodes.length],
                ['edges', 'Edges', workflow.edges.length],
              ] as const
            ).map(([id, label, count]) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={listTab === id}
                onClick={() => setListTab(id)}
                className={`text-label-md flex items-center gap-1.5 rounded-lg px-3 py-1 ${
                  listTab === id
                    ? 'bg-muted text-on-surface'
                    : 'text-on-surface-variant hover:bg-muted'
                }`}
              >
                {label}
                <span className="text-body-sm text-muted-foreground tabular-nums">
                  {count}
                </span>
              </button>
            ))}
          </div>

          {listTab === 'nodes' && (
            <>
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

              <input
                type="search"
                aria-label="Find node"
                placeholder="Find node or plugin"
                value={nodeQuery}
                onChange={(event) => setNodeQuery(event.target.value)}
                className="bg-muted text-body-md placeholder:text-muted-foreground focus-visible:ring-ring/40 h-8 rounded-full px-3 outline-none focus-visible:ring-2"
              />

              <ul className="flex flex-col gap-0.5" aria-label="Nodes">
                {workflow.nodes
                  .filter((n) =>
                    `${n.nodeId} ${n.type}`
                      .toLowerCase()
                      .includes(nodeQuery.trim().toLowerCase())
                  )
                  // a stage's nodes sit together, under its name, where its first node is
                  .sort(
                    (a, b) =>
                      (stageRank.get(stageOf(a)) as number) -
                      (stageRank.get(stageOf(b)) as number)
                  )
                  .map((n, index, shown) => (
                    <li key={n.nodeId}>
                      {stages.has(stageOf(n)) &&
                        stageOf(shown[index - 1] ?? { nodeId: '' }) !==
                          stageOf(n) && (
                          <p
                            className="text-overline flex items-center gap-2 px-3 pt-3 pb-1"
                            data-testid={`outline-stage-${stageOf(n)}`}
                          >
                            {stageOf(n)}
                            <span className="text-muted-foreground tabular-nums">
                              {stages.get(stageOf(n))?.length}
                            </span>
                          </p>
                        )}
                      <button
                        type="button"
                        aria-current={n.nodeId === selected}
                        onClick={() => setSelected(n.nodeId)}
                        className={`text-body-md transition-standard flex w-full items-baseline gap-2 rounded-lg px-3 py-2 text-left ${
                          n.nodeId === selected
                            ? 'bg-accent text-accent-foreground font-semibold'
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
            </>
          )}

          {listTab === 'edges' && (
            <div className="flex flex-col gap-2">
              <h2 className="sr-only">Edges</h2>
              <ul
                aria-label="Edges"
                className="text-body-md flex flex-col gap-1"
              >
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
                      className="text-body-sm text-error ml-auto"
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
                  {/* no port is the "default" port; "*" takes every port but "error" */}
                  <option value="">no port (default)</option>
                  <option value="*">all ports</option>
                  {(ports[edge.source] ?? []).map((port) => (
                    <option key={port}>{port}</option>
                  ))}
                </NativeSelect>
                <NativeSelect
                  id="edge-target"
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
                <button
                  type="button"
                  className={BUTTON}
                  onClick={() => addEdge()}
                >
                  Add edge
                </button>
              </div>
              <p className="text-body-sm text-on-surface-variant">
                Validate to list a node&apos;s ports for its configuration.
              </p>
            </div>
          )}
        </aside>

        <div
          data-testid="editor-canvas"
          className="relative h-80 min-w-0 shrink-0 lg:h-auto lg:flex-1"
        >
          {stages.size > 0 && (
            <GroupingSwitch grouped={grouped} onChange={setGrouped} />
          )}
          {/* Re-keyed on selection so the view opens on the selected node and its neighbours */}
          <WorkflowCanvas
            key={`${workflow.nodes.length}/${selected ?? ''}`}
            graph={graph}
            tasksByNode={NO_TASKS}
            stageView={stageView}
            onConnect={(source, target, sourcePort) =>
              addEdge({ source, target, sourcePort })
            }
            focusOn={
              selected
                ? [
                    selected,
                    ...workflow.edges
                      .filter((e) => e.source === selected)
                      .map((e) => e.target),
                    ...workflow.edges
                      .filter((e) => e.target === selected)
                      .map((e) => e.source),
                  ]
                : undefined
            }
          />
        </div>

        <aside
          aria-label="Node inspector"
          className="border-border w-full shrink-0 border-t p-4 lg:w-96 lg:overflow-y-auto lg:border-t-0 lg:border-l"
        >
          {node ? (
            <JsonValidityContext.Provider value={reportJson}>
              <NodeInspector
                key={`${node.nodeId}/${revision}`}
                node={node}
                onRemove={() => removeNode(node.nodeId)}
                onGroupChange={(group) =>
                  update({
                    // an undefined group is left out of the saved JSON
                    nodes: workflow.nodes.map((n) =>
                      n.nodeId === node.nodeId
                        ? { ...n, group: group ?? undefined }
                        : n
                    ),
                  })
                }
                edges={workflow.edges}
                onRemoveEdge={(gone) =>
                  update({
                    edges: workflow.edges.filter((edge) => edge !== gone),
                  })
                }
                onAddEdge={() => {
                  setListTab('edges');
                  setEdge({
                    source: node.nodeId,
                    target: '',
                    sourcePort: null,
                  });
                }}
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
