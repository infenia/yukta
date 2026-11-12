// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import {
  Background,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { cn } from '@/lib/utils';
import { useOwnedNodes } from '../hooks/useOwnedNodes';
import { useSupervisorTypes } from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import {
  supervisionGraph,
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
const FIT = { padding: 0.2, maxZoom: 1 };

/** Fits the view again when the box is resized or the boxes change: the first fit runs too early. */
function Refit({ signature }: { signature: string }) {
  const { fitView } = useReactFlow();
  const width = useStore((s) => s.width);
  useEffect(() => {
    void fitView(FIT);
  }, [fitView, width, signature]);
  return null;
}

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
  const { targets, ownedNodes } = useOwnedNodes(supervisor.id);
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
      <ReactFlowProvider>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          onNodeClick={onClick}
          fitView
          fitViewOptions={FIT}
          nodesDraggable={false}
          nodesConnectable={false}
          proOptions={{ hideAttribution: true }}
        >
          <Background gap={16} size={1} />
          <Refit signature={graph.nodes.map((n) => n.id).join('|')} />
        </ReactFlow>
      </ReactFlowProvider>
    </section>
  );
}
