// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect } from 'react';
import {
  Background,
  BackgroundVariant,
  Handle,
  MiniMap,
  Panel,
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
import { ZoomControls } from '@/features/workflow/components/WorkflowCanvas';
import type { MapEdge, MapKind, MapNode } from '../lib/supervisionGraph';

type CardData = Pick<
  MapNode,
  'kind' | 'label' | 'sub' | 'icon' | 'color' | 'dry' | 'toggle'
> & { clickable: boolean; small: boolean };

const KIND_ICON: Record<MapKind, string> = {
  maker: 'shield_person',
  parent: 'shield_person',
  self: 'shield_person',
  child: 'shield_person',
  supervisor: 'shield_person',
  workflow: 'account_tree',
  nodes: 'hub',
  target: 'hub',
  direct: 'person',
  more: 'more_horiz',
};

const KIND_BOX: Partial<Record<MapKind, string>> = {
  self: 'border-primary ring-primary/30 ring-2',
  maker: 'border-dashed',
  more: 'border-dashed text-on-surface-variant',
  target: 'border-dashed',
};

/** A box of the map: an icon tile, the name, and what it is; the same look as a node of a workflow. */
function Card({ data }: NodeProps<Node<CardData>>) {
  return (
    <div
      className={cn(
        'border-border bg-card shadow-card text-on-surface flex size-full items-center gap-2 rounded-xl border',
        data.small ? 'px-2' : 'px-2.5',
        KIND_BOX[data.kind],
        data.clickable && 'hover:border-primary cursor-pointer'
      )}
    >
      <Handle type="target" position={Position.Left} className="opacity-0" />
      <span
        aria-hidden="true"
        className={cn(
          'bg-primary/10 text-primary flex shrink-0 items-center justify-center rounded-lg',
          data.small ? 'size-6' : 'size-9',
          data.kind === 'direct' && 'bg-muted text-on-surface-variant'
        )}
        style={data.color ? { color: data.color } : undefined}
      >
        <span
          className={cn(
            'material-symbols-outlined',
            data.small ? 'text-base' : 'text-xl'
          )}
        >
          {data.icon ?? KIND_ICON[data.kind]}
        </span>
      </span>
      <span className="min-w-0 flex-1">
        <span className="text-label-md block truncate">{data.label}</span>
        {data.sub && (
          <span
            className={cn(
              'text-code-sm block truncate text-[11px]',
              data.dry ? 'text-attention' : 'text-on-surface-variant'
            )}
          >
            {data.sub}
          </span>
        )}
      </span>
      <Handle type="source" position={Position.Right} className="opacity-0" />
    </div>
  );
}

const NODE_TYPES = { card: Card };
const FIT = { padding: 0.2, maxZoom: 1 };

/** The line style of an edge, by whether it is dashed (switched off). */
const DASH_STYLE: Record<
  string,
  { strokeDasharray: string; opacity: number } | undefined
> = {
  true: { strokeDasharray: '5 4', opacity: 0.6 },
  false: undefined,
};

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
 * The canvas of the supervision maps: boxes with icon tiles, smooth edges (dashed for what is
 * switched off), zoom tools and a minimap, like the workflow canvas.
 */
export function SupervisionCanvas({
  label,
  nodes,
  edges,
  onNodeClick,
  className,
}: {
  label: string;
  nodes: MapNode[];
  edges: MapEdge[];
  /** What a click on a box does. */
  onNodeClick: (node: MapNode) => void;
  className?: string;
}) {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const flowNodes: Node[] = nodes.map((n) => ({
    id: n.id,
    type: 'card',
    position: { x: n.x, y: n.y },
    width: n.width,
    height: n.height,
    data: {
      kind: n.kind,
      label: n.label,
      sub: n.sub,
      icon: n.icon,
      color: n.color,
      dry: n.dry,
      toggle: n.toggle,
      small: n.height < 50,
      clickable: Boolean(n.href || n.supervisor || n.toggle),
    },
  }));
  const flowEdges: Edge[] = edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label,
    type: 'smoothstep',
    style: DASH_STYLE[String(e.dashed)],
  }));
  return (
    <section
      aria-label={label}
      className={cn(
        'border-border bg-card shadow-card overflow-hidden rounded-xl border',
        className
      )}
    >
      <ReactFlowProvider>
        <ReactFlow
          nodes={flowNodes}
          edges={flowEdges}
          nodeTypes={NODE_TYPES}
          onNodeClick={(_, node) => onNodeClick(byId.get(node.id) as MapNode)}
          fitView
          fitViewOptions={FIT}
          minZoom={0.2}
          nodesDraggable={false}
          nodesConnectable={false}
          proOptions={{ hideAttribution: true }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1.5} />
          <Panel position="bottom-left" className="m-3!">
            <ZoomControls />
          </Panel>
          <MiniMap
            // no room for a minimap on a phone-sized canvas (! beats xyflow's unlayered CSS)
            className="max-md:hidden!"
            style={{ width: 120, height: 80 }}
            pannable
            zoomable
            nodeStrokeWidth={0}
            nodeColor="var(--color-primary)"
          />
          <Refit signature={nodes.map((n) => n.id).join('|')} />
        </ReactFlow>
      </ReactFlowProvider>
    </section>
  );
}
