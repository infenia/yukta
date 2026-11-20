// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, type ReactNode } from 'react';
import {
  Background,
  BackgroundVariant,
  Panel,
  MiniMap,
  ReactFlow,
  useReactFlow,
} from '@xyflow/react';
import { categoryAccent } from '../lib/categoryAccent';
import { paletteColor } from '../lib/paletteColor';
import type { StageData, WorkflowFlowNode } from '../lib/layout';
import '@xyflow/react/dist/style.css';
import { WorkflowNodeCard } from './nodes/WorkflowNodeCard';
import { StageFrame, StageNodeCard } from './nodes/StageNodes';
import { findNodes } from '../lib/findNodes';
import { layoutGraph } from '../lib/layout';
import type { TaskProgress, WorkflowGraph } from '../types/workflow';

import { IconButton } from '@/components/common/IconButton';

const NODE_TYPES = {
  workflowNode: WorkflowNodeCard,
  stageNode: StageNodeCard,
  stageFrame: StageFrame,
};

/** Above this many nodes the whole graph fits only as an unreadable strip. */
const LARGE_GRAPH = 30;
/** What a large graph opens on when nothing is running: its start. */
const OPENING_NODES = 12;

/** Keeps the given nodes in view: fits to them again whenever the set changes. */
function FollowNodes({ ids }: { ids: string }) {
  const { fitView } = useReactFlow();
  useEffect(() => {
    if (ids) {
      void fitView({
        nodes: ids.split('\n').map((id) => ({ id })),
        padding: 0.3,
        // pan to one running node without blowing it up
        maxZoom: 1,
        duration: 300,
      });
    }
  }, [ids, fitView]);
  return null;
}

/**
 * Centres one node when asked (a search result picked). The node may only appear after the layout
 * settles (an opened stage, a side panel narrowing the canvas), so wait until it is measured.
 */
function CenterNode({ target }: { target?: { id: string; nonce: number } }) {
  const { fitView, getNode } = useReactFlow();
  useEffect(() => {
    if (!target) return undefined;
    let tries = 0;
    const timer = setInterval(() => {
      tries += 1;
      if (getNode(target.id)?.measured?.width || tries > 20) {
        clearInterval(timer);
        void fitView({
          nodes: [{ id: target.id }],
          padding: 0.5,
          maxZoom: 1,
          duration: 400,
        });
      }
    }, 100);
    return () => clearInterval(timer);
  }, [target, fitView, getNode]);
  return null;
}

/** Zoom in, zoom out and fit: one more vertical pill, as the canvas tools above it. */
function ZoomControls() {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const buttons: [string, string, () => void][] = [
    ['add', 'Zoom in', () => void zoomIn({ duration: 150 })],
    ['remove', 'Zoom out', () => void zoomOut({ duration: 150 })],
    ['fullscreen', 'Fit view', () => void fitView({ duration: 300 })],
  ];
  return (
    <div
      role="toolbar"
      aria-orientation="vertical"
      aria-label="Zoom"
      className="border-border bg-card/80 shadow-card pointer-events-auto flex flex-col items-center gap-1 rounded-full border p-1 backdrop-blur-sm"
    >
      {buttons.map(([icon, label, onClick]) => (
        <IconButton
          key={label}
          icon={icon}
          label={label}
          onClick={onClick}
          tone="neutral"
          className="size-8 rounded-full border-0"
        />
      ))}
    </div>
  );
}

/** The workflow DAG canvas: pan/zoom viewport of backend-driven node placeholders. */
export function WorkflowCanvas({
  graph,
  tasksByNode,
  focusOn,
  stageView,
  onConnect,
  onNodeClick,
  follow = false,
  find = '',
  centerOn,
  tools,
}: {
  graph: WorkflowGraph;
  tasksByNode: Record<string, TaskProgress | undefined>;
  /** Node IDs to open on, instead of the automatic choice (the editor's selection). */
  focusOn?: string[];
  /** Group nodes into stages: the open ones show their nodes, the others one box each. */
  stageView?: { open: ReadonlySet<string>; onToggle: (stage: string) => void };
  /** Lets edges be drawn by dragging from an output port to a node; the port is the handle's. */
  onConnect?: (
    source: string,
    target: string,
    sourcePort: string | null
  ) => void;
  /** A workflow node was clicked, with its node ID. */
  onNodeClick?: (nodeId: string) => void;
  /** Keep the running nodes in view as the run moves on. */
  follow?: boolean;
  /** Bring the nodes this matches (ID, type, stage, description, edge) into view (it wins over follow). */
  find?: string;
  /** Pan to this node (a new nonce centres it again). */
  centerOn?: { id: string; nonce: number };
  /** Tool buttons for the top left of the canvas (the page's, such as search and views). */
  tools?: ReactNode;
}) {
  const connectable = onConnect !== undefined;
  const { nodes, edges } = useMemo(() => {
    const laid = layoutGraph(graph, tasksByNode, stageView);
    return connectable
      ? { ...laid, nodes: laid.nodes.map((node) => ({ ...node, connectable })) }
      : laid;
  }, [graph, tasksByNode, stageView, connectable]);

  // A large graph opens on its running nodes (else its first few); the minimap shows the rest
  const running = nodes.filter(
    (node) => node.type !== 'stageFrame' && node.data.status === 'running'
  );
  const matched = new Set(findNodes(graph, find).map((match) => match.nodeId));
  const found = nodes
    .filter((node) => matched.has(node.id))
    .map((node) => node.id)
    .join('\n');
  const focus = focusOn
    ? focusOn.map((id) => ({ id }))
    : nodes.length <= LARGE_GRAPH
      ? undefined
      : (running.length > 0 ? running : nodes.slice(0, OPENING_NODES)).map(
          (node) => ({ id: node.id })
        );

  return (
    <div className="size-full" data-testid="workflow-canvas">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        fitViewOptions={{ nodes: focus, padding: 0.15 }}
        // fitView stops at minZoom; xyflow's default (0.5) leaves a wide graph cut off on a
        // phone-width canvas, so let it zoom out further
        minZoom={0.2}
        nodesDraggable={false}
        nodesConnectable={connectable}
        onConnect={(connection) =>
          onConnect?.(
            connection.source,
            connection.target,
            connection.sourceHandle ?? null
          )
        }
        elementsSelectable
        // a stage box or frame isn't a node the logs know
        onNodeClick={(_, node) =>
          node.type === 'workflowNode' && onNodeClick?.(node.id)
        }
        proOptions={{ hideAttribution: true }}
      >
        {found ? (
          <FollowNodes ids={found} />
        ) : (
          follow && (
            <FollowNodes ids={running.map((node) => node.id).join('\n')} />
          )
        )}
        <CenterNode target={centerOn} />
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.5} />
        {/* One column on the left: the page's tools at the top, zoom at the bottom, so the two can
            never overlap; it is inside the flow, which clips it when the canvas is covered */}
        <Panel
          position="top-left"
          className="pointer-events-none m-3! flex h-[calc(100%-1.5rem)] flex-col justify-between gap-2"
        >
          <div>{tools}</div>
          <ZoomControls />
        </Panel>
        <MiniMap
          // No room for a minimap on a phone-sized canvas (! beats xyflow's unlayered CSS)
          className="max-md:hidden!"
          style={{ width: 120, height: 80 }}
          pannable
          zoomable
          nodeStrokeWidth={0}
          nodeColor={(node) =>
            node.type === 'workflowNode'
              ? (paletteColor((node as WorkflowFlowNode).data.node.color) ??
                categoryAccent((node as WorkflowFlowNode).data.node.category))
              : (paletteColor((node.data as StageData).color) ??
                'var(--color-outline-variant)')
          }
        />
      </ReactFlow>
    </div>
  );
}
