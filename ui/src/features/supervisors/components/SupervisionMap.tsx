// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { useQueries } from '@tanstack/react-query';
import {
  Background,
  Handle,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { cn } from '@/lib/utils';
import { getOwnership } from '../api/supervision';
import {
  useAttachments,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { parseScope } from '../lib/ownership';
import {
  MAX_WORKFLOWS,
  supervisionGraph,
  workflowKey,
  type MapKind,
  type MapNode,
} from '../lib/supervisionGraph';
import type { SupervisorView } from '../types';

type CardData = Pick<MapNode, 'kind' | 'label' | 'sub' | 'href' | 'supervisor'>;

const STYLE: Record<MapKind, string> = {
  maker: 'border-dashed',
  parent: '',
  self: 'border-primary bg-accent font-semibold',
  child: '',
  workflow: '',
  nodes: 'text-code-sm',
  more: 'border-dashed text-on-surface-variant',
  target: 'border-dashed',
};

function Card({ data }: NodeProps<Node<CardData>>) {
  const clickable = Boolean(
    data.href || data.supervisor || data.kind === 'more'
  );
  return (
    <div
      className={cn(
        'border-border bg-card text-body-sm text-on-surface flex size-full flex-col justify-center rounded-xl border px-3',
        STYLE[data.kind],
        clickable && 'hover:border-primary cursor-pointer'
      )}
    >
      <Handle type="target" position={Position.Left} className="opacity-0" />
      <span className="truncate">{data.label}</span>
      {data.sub && (
        <span className="text-code-sm text-on-surface-variant truncate text-[11px]">
          {data.sub}
        </span>
      )}
      <Handle type="source" position={Position.Right} className="opacity-0" />
    </div>
  );
}

const NODE_TYPES = { card: Card };

/**
 * Who decides, who this supervisor reports to and who reports to it, the workflows it owns and the
 * nodes in them. A workflow, node or supervisor opens on a click; "+N more" opens the Owns tab.
 */
export function SupervisionMap({
  supervisor,
  all,
  onSelect,
  onMore,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
  onMore: () => void;
}) {
  const navigate = useNavigate();
  const types = useSupervisorTypes();
  const owned = useAttachments({ supervisor: supervisor.id });
  const targets = useMemo(
    () => (owned.data ?? []).map((a) => parseScope(a.scope)),
    [owned.data]
  );
  // the nodes of a whole-workflow assignment come from the ownership map, for the workflows drawn
  const whole = useMemo(() => {
    const seen = new Map<string, (typeof targets)[number]>();
    for (const t of targets) {
      if (t.workflowId && t.sessionId && t.selectors.length === 0) {
        seen.set(workflowKey(t), t);
      }
    }
    return [...seen].slice(0, MAX_WORKFLOWS);
  }, [targets]);
  const ownership = useQueries({
    queries: whole.map(([, t]) => ({
      queryKey: ['supervision', 'ownership', t.sessionId, t.workflowId],
      queryFn: () =>
        getOwnership(t.sessionId as string, t.workflowId as string),
    })),
  });
  const ownedNodes = Object.fromEntries(
    whole.map(([key], i) => [
      key,
      Object.entries(ownership[i]?.data?.nodes ?? {})
        .filter(([, owner]) => owner === supervisor.id)
        .map(([node]) => node),
    ])
  );
  const maker = decisionMaker(supervisor.type, types.data);
  // dagre on a handful of boxes is cheap, so it runs on every render
  const graph = supervisionGraph({
    supervisor,
    all,
    maker: { name: maker.name, detail: supervisor.type },
    targets,
    ownedNodes,
  });
  const nodes: Node[] = graph.nodes.map((n) => ({
    id: n.id,
    type: 'card',
    position: { x: n.x, y: n.y },
    width: n.width,
    height: n.height,
    data: {
      kind: n.kind,
      label: n.label,
      sub: n.sub,
      href: n.href,
      supervisor: n.supervisor,
    },
  }));
  const edges: Edge[] = graph.edges.map((e) => ({ ...e, type: 'smoothstep' }));
  const onClick = (_: unknown, node: Node) => {
    const data = node.data as unknown as CardData;
    if (data.supervisor) onSelect(data.supervisor);
    else if (data.href) void navigate(data.href);
    else if (data.kind === 'more') onMore();
  };

  return (
    <section
      aria-label="Supervision map"
      className="border-border bg-card shadow-card h-72 overflow-hidden rounded-xl border"
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onNodeClick={onClick}
        fitView
        fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
        nodesDraggable={false}
        nodesConnectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={16} size={1} />
      </ReactFlow>
    </section>
  );
}
