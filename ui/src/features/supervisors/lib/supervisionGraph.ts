// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import dagre from '@dagrejs/dagre';
import { targetLabel, type Target } from './ownership';

/** At most this many workflows are drawn; the rest fold into "+N more". */
export const MAX_WORKFLOWS = 6;
/** More nodes than this in one workflow fold into one "N nodes" box. */
export const FOLD_NODES = 5;

export type MapKind =
  | 'maker'
  | 'parent'
  | 'self'
  | 'child'
  | 'workflow'
  | 'nodes'
  | 'more'
  | 'target';

export interface MapNode {
  id: string;
  kind: MapKind;
  label: string;
  sub?: string;
  /** Where a click goes: a route; or a supervisor id when `supervisor` is set. */
  href?: string;
  supervisor?: string;
  width: number;
  height: number;
  x: number;
  y: number;
}

export interface MapEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
}

export interface MapInput {
  supervisor: {
    id: string;
    parents?: string[] | null;
    delegation?: Record<string, { mode: string }>;
  };
  all: { id: string; parents?: string[] | null }[];
  maker: { name: string; detail?: string };
  targets: Target[];
  /** The nodes a whole-workflow assignment owns, by `<session>/<workflow>`, from the ownership map. */
  ownedNodes?: Record<string, string[]>;
}

/** The key of a workflow target in `ownedNodes`. */
export const workflowKey = (t: Pick<Target, 'sessionId' | 'workflowId'>) =>
  `${t.sessionId ?? ''}/${t.workflowId}`;

const SIZE = { width: 190, height: 52 };
const SMALL = { width: 170, height: 40 };
const enc = encodeURIComponent;
// a plain node id; a selector such as `deploy.*` or `group:ci` is not one
const isNodeId = (text: string) => /^[\w-]+(\.[\w-]+)*$/.test(text);
const workflowHref = (t: Target) =>
  t.sessionId
    ? `/sessions/${enc(t.sessionId)}/workflow/${enc(t.workflowId ?? '')}`
    : undefined;

/**
 * The supervision map as plain data, laid out left to right: the decision maker and the parents,
 * this supervisor (its children after it), the workflows it owns, then the nodes. A pure function so
 * the layout can be tested without a browser.
 */
export function supervisionGraph(input: MapInput): {
  nodes: MapNode[];
  edges: MapEdge[];
  /** Workflows left out of the drawing. */
  hidden: number;
} {
  const { supervisor, all, maker, targets, ownedNodes = {} } = input;
  const nodes: Omit<MapNode, 'x' | 'y'>[] = [];
  const edges: MapEdge[] = [];
  const add = (
    n: Omit<MapNode, 'x' | 'y' | 'width' | 'height'>,
    small = false
  ) => nodes.push({ ...n, ...(small ? SMALL : SIZE) });
  const link = (source: string, target: string, label?: string) =>
    edges.push({ id: `${source}->${target}`, source, target, label });

  add({ id: 'self', kind: 'self', label: supervisor.id });
  add({ id: 'maker', kind: 'maker', label: maker.name, sub: maker.detail });
  link('maker', 'self');
  const modes = new Set(
    Object.values(supervisor.delegation ?? {}).map((d) => d.mode.toLowerCase())
  );
  for (const parent of supervisor.parents ?? []) {
    add({
      id: `parent:${parent}`,
      kind: 'parent',
      label: parent,
      supervisor: parent,
    });
    link(`parent:${parent}`, 'self', [...modes].join(' / ') || 'ordered');
  }
  for (const child of all.filter((s) =>
    (s.parents ?? []).includes(supervisor.id)
  )) {
    add({
      id: `child:${child.id}`,
      kind: 'child',
      label: child.id,
      supervisor: child.id,
    });
    link('self', `child:${child.id}`);
  }

  // one box per workflow, however many assignments name it
  const workflows = new Map<string, { target: Target; items: Set<string> }>();
  const others: Target[] = [];
  for (const t of targets) {
    if (t.workflowId && (t.kind === 'workflow' || t.kind === 'run')) {
      const key = workflowKey(t);
      const entry = workflows.get(key) ?? {
        target: t,
        items: new Set<string>(),
      };
      t.selectors.forEach((s) => entry.items.add(s));
      (ownedNodes[key] ?? []).forEach((n) => entry.items.add(n));
      workflows.set(key, entry);
    } else others.push(t);
  }
  const drawn = [...workflows].slice(0, MAX_WORKFLOWS);
  const hidden = workflows.size - drawn.length;
  for (const [key, { target, items }] of drawn) {
    const id = `workflow:${key}`;
    add({
      id,
      kind: 'workflow',
      label: target.workflowId ?? '',
      sub: target.sessionId ?? undefined,
      href: workflowHref(target),
    });
    link('self', id);
    const list = [...items];
    if (list.length > FOLD_NODES) {
      add(
        {
          id: `${id}#nodes`,
          kind: 'nodes',
          label: `${list.length} nodes`,
          href: workflowHref(target),
        },
        true
      );
      link(id, `${id}#nodes`);
    } else {
      for (const item of list) {
        const base = workflowHref(target);
        add(
          {
            id: `${id}#${item}`,
            kind: 'nodes',
            label: item,
            href:
              base && isNodeId(item)
                ? `${base}?node=${enc(item)}&tab=supervisor`
                : base,
          },
          true
        );
        link(id, `${id}#${item}`);
      }
    }
  }
  if (hidden > 0) {
    add({ id: 'more', kind: 'more', label: `+${hidden} more` }, true);
    link('self', 'more');
  }
  others.forEach((t, i) => {
    add({ id: `target:${i}`, kind: 'target', label: targetLabel(t) });
    link('self', `target:${i}`);
  });

  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: 'LR', nodesep: 24, ranksep: 70 });
  graph.setDefaultEdgeLabel(() => ({}));
  nodes.forEach((n) =>
    graph.setNode(n.id, { width: n.width, height: n.height })
  );
  edges.forEach((e) => graph.setEdge(e.source, e.target));
  dagre.layout(graph);
  return {
    nodes: nodes.map((n) => {
      const p = graph.node(n.id);
      return { ...n, x: p.x - n.width / 2, y: p.y - n.height / 2 };
    }),
    edges,
    hidden,
  };
}
