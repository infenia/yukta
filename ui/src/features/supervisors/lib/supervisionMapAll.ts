// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { parseScope, targetLabel } from './ownership';
import {
  FOLD_NODES,
  SMALL,
  SIZE,
  layoutGraph,
  workflowKey,
  type MapEdge,
  type MapNode,
} from './supervisionGraph';

/** At most this many workflows are drawn on the whole map; the rest fold into "+N more". */
export const MAX_MAP_WORKFLOWS = 16;

export interface MapAllInput {
  supervisors: {
    id: string;
    type: string;
    parents?: string[] | null;
    dryRun: boolean;
  }[];
  /** The decision maker of a type: its name, icon and colour. */
  maker: (type: string) => { name: string; icon: string; color: string };
  attachments: { supervisorId: string; scope: string; enabled: boolean }[];
  /** Workflows nobody owns. */
  unowned: { sessionId: string; workflowId: string }[];
  /** The owner of each node, for the workflows opened on the map, by `<session>/<workflow>`. */
  opened: Record<string, Record<string, string>>;
}

const enc = encodeURIComponent;

/**
 * Everything on one map, left to right: the decision makers, the supervisors with who reports to
 * whom, the workflows they own (dashed when the assignment is off), and Direct control with the
 * workflows nobody owns. A workflow opened on the map also shows its nodes with their owners.
 * A pure function, so the layout can be tested without a browser.
 */
export function supervisionMapAll(input: MapAllInput): {
  nodes: MapNode[];
  edges: MapEdge[];
  /** Workflows left out of the drawing. */
  hidden: number;
} {
  const nodes: Omit<MapNode, 'x' | 'y'>[] = [];
  // keyed by id, so a link named twice is drawn once
  const edgeById = new Map<string, MapEdge>();
  const seen = new Set<string>();
  const add = (
    n: Omit<MapNode, 'x' | 'y' | 'width' | 'height'>,
    small = false
  ) => {
    if (!seen.has(n.id)) {
      seen.add(n.id);
      nodes.push({ ...n, ...(small ? SMALL : SIZE) });
    }
  };
  const link = (source: string, target: string, dashed = false) => {
    const id = `${source}->${target}`;
    edgeById.set(id, { id, source, target, dashed });
  };

  for (const s of input.supervisors) {
    const maker = input.maker(s.type);
    add({
      id: `maker:${s.type}`,
      kind: 'maker',
      label: maker.name,
      sub: s.type,
      icon: maker.icon,
      color: maker.color,
    });
    add({
      id: `supervisor:${s.id}`,
      kind: 'supervisor',
      label: s.id,
      sub: s.dryRun ? 'Dry run' : undefined,
      dry: s.dryRun,
      supervisor: s.id,
    });
    link(`maker:${s.type}`, `supervisor:${s.id}`);
    for (const parent of s.parents ?? []) {
      link(`supervisor:${parent}`, `supervisor:${s.id}`);
    }
  }

  // one box per workflow, however many supervisors name it
  const workflows = new Map<
    string,
    {
      sessionId: string | null;
      workflowId: string;
      owners: Map<string, boolean>;
    }
  >();
  const others: { supervisorId: string; label: string; id: string }[] = [];
  for (const a of input.attachments) {
    const t = parseScope(a.scope);
    if (t.workflowId && (t.kind === 'workflow' || t.kind === 'run')) {
      const key = workflowKey(t);
      const entry = workflows.get(key) ?? {
        sessionId: t.sessionId,
        workflowId: t.workflowId,
        owners: new Map<string, boolean>(),
      };
      // a supervisor with one live assignment on it is drawn solid
      entry.owners.set(
        a.supervisorId,
        (entry.owners.get(a.supervisorId) ?? false) || a.enabled
      );
      workflows.set(key, entry);
    } else {
      others.push({
        supervisorId: a.supervisorId,
        label: targetLabel(t),
        id: `target:${a.supervisorId}:${a.scope}`,
      });
    }
  }
  const unowned = input.unowned.map((w) => ({
    key: workflowKey(w),
    ...w,
  }));
  const drawn = [...workflows].slice(0, MAX_MAP_WORKFLOWS);
  const hidden = workflows.size - drawn.length;

  const addWorkflow = (
    key: string,
    sessionId: string | null,
    workflowId: string
  ) => {
    add({
      id: `workflow:${key}`,
      kind: 'workflow',
      label: workflowId,
      sub: sessionId ?? undefined,
      // a workflow with no session has no ownership map to open
      toggle: sessionId ? key : undefined,
      href: sessionId
        ? `/sessions/${enc(sessionId)}/workflow/${enc(workflowId)}`
        : undefined,
    });
    const owned = input.opened[key];
    if (!owned) return;
    const list = Object.entries(owned);
    for (const [node, owner] of list.slice(0, FOLD_NODES * 2)) {
      add(
        {
          id: `workflow:${key}#${node}`,
          kind: 'nodes',
          label: node,
          sub: owner === 'default' ? 'Direct control' : owner,
        },
        true
      );
      link(`workflow:${key}`, `workflow:${key}#${node}`);
    }
    if (list.length > FOLD_NODES * 2) {
      add(
        {
          id: `workflow:${key}#more`,
          kind: 'more',
          label: `+${list.length - FOLD_NODES * 2} nodes`,
        },
        true
      );
      link(`workflow:${key}`, `workflow:${key}#more`);
    }
  };

  for (const [key, w] of drawn) {
    addWorkflow(key, w.sessionId, w.workflowId);
    for (const [owner, live] of w.owners) {
      link(`supervisor:${owner}`, `workflow:${key}`, !live);
    }
  }
  if (hidden > 0) {
    add({ id: 'more', kind: 'more', label: `+${hidden} more workflows` }, true);
  }
  for (const o of others) {
    add({ id: o.id, kind: 'target', label: o.label });
    link(`supervisor:${o.supervisorId}`, o.id);
  }
  if (unowned.length > 0) {
    add({
      id: 'direct',
      kind: 'direct',
      label: 'Direct control',
      sub: `${unowned.length} ${unowned.length === 1 ? 'workflow' : 'workflows'}`,
    });
    for (const w of unowned.slice(0, MAX_MAP_WORKFLOWS)) {
      addWorkflow(w.key, w.sessionId, w.workflowId);
      link('direct', `workflow:${w.key}`);
    }
  }

  const edges = [...edgeById.values()];
  return { nodes: layoutGraph(nodes, edges), edges, hidden };
}
