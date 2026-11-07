// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  Background,
  BackgroundVariant,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { WorkflowNodeCard } from '@/features/workflow/components/nodes/WorkflowNodeCard';
import {
  DEFAULT_NODE_HEIGHT,
  DEFAULT_NODE_WIDTH,
  type WorkflowFlowNode,
} from '@/features/workflow/lib/layout';
import type { PluginDetails } from '../types';

/** A dashed stand-in for "whatever connects here", so the card shows how it sits in a flow. */
function GhostNode({ data }: NodeProps<Node<{ label: string }>>) {
  return (
    <div className="border-border text-body-sm text-on-surface-variant flex size-full items-center justify-center rounded-xl border border-dashed px-3 text-center">
      <Handle type="target" position={Position.Left} className="opacity-0" />
      {data.label}
      <Handle type="source" position={Position.Right} className="opacity-0" />
    </div>
  );
}

const NODE_TYPES = { workflowNode: WorkflowNodeCard, ghost: GhostNode };
const GHOST = { width: 150, height: 48 };
const GAP = 80;
const MAX_GHOSTS = 3;
const GHOST_TOP = (DEFAULT_NODE_HEIGHT - GHOST.height) / 2;

/** Renders the real canvas node card for a plugin, between dashed neighbours, so the preview can't drift from the canvas. */
export function PluginCanvasPreview({ plugin }: { plugin: PluginDetails }) {
  const node: WorkflowFlowNode = {
    id: plugin.type,
    type: 'workflowNode',
    position: { x: 0, y: 0 },
    width: DEFAULT_NODE_WIDTH,
    height: DEFAULT_NODE_HEIGHT,
    data: {
      status: 'pending',
      hideDescription: true,
      hideStatus: true,
      node: { ...plugin, nodeId: plugin.type },
    },
  };
  const nodes: Node[] = [node as Node];
  const edges: Edge[] = [];

  if (plugin.category !== 'TRIGGER') {
    nodes.push({
      id: 'in',
      type: 'ghost',
      position: { x: -GHOST.width - GAP, y: GHOST_TOP },
      ...GHOST,
      data: { label: 'Any earlier node' },
    });
    edges.push({ id: 'e-in', source: 'in', target: node.id });
  }
  if (plugin.category !== 'TERMINAL') {
    const ports = plugin.outputPorts.length > 0 ? plugin.outputPorts : [null];
    ports.slice(0, MAX_GHOSTS).forEach((port, i, shown) => {
      nodes.push({
        id: `out-${i}`,
        type: 'ghost',
        position: {
          x: DEFAULT_NODE_WIDTH + GAP,
          y: GHOST_TOP + (i - (shown.length - 1) / 2) * (GHOST.height + 16),
        },
        ...GHOST,
        data: { label: port ? `Next, on “${port}”` : 'Next node' },
      });
      edges.push({
        id: `e-out-${i}`,
        source: node.id,
        sourceHandle: port ?? undefined,
        target: `out-${i}`,
      });
    });
  }

  return (
    <div
      className="border-border bg-card shadow-card h-60 overflow-hidden rounded-xl border"
      data-testid="plugin-canvas-preview"
    >
      <ReactFlowProvider>
        <ReactFlow
          key={plugin.type}
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          fitView
          fitViewOptions={{ maxZoom: 1.25, padding: 0.15 }}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          panOnDrag={false}
          zoomOnScroll={false}
          zoomOnPinch={false}
          zoomOnDoubleClick={false}
          preventScrolling={false}
          proOptions={{ hideAttribution: true }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1.5} />
        </ReactFlow>
      </ReactFlowProvider>
    </div>
  );
}
