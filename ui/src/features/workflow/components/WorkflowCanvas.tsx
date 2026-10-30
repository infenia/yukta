// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  useReactFlow,
} from '@xyflow/react';
import { categoryAccent } from '../lib/categoryAccent';
import type { WorkflowFlowNode } from '../lib/layout';
import '@xyflow/react/dist/style.css';
import { WorkflowNodeCard } from './nodes/WorkflowNodeCard';
import { StageFrame, StageNodeCard } from './nodes/StageNodes';
import { layoutGraph } from '../lib/layout';
import type { TaskProgress, WorkflowGraph } from '../types/workflow';

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

/** The workflow DAG canvas: pan/zoom viewport of backend-driven node placeholders. */
export function WorkflowCanvas({
  graph,
  tasksByNode,
  focusOn,
  stageView,
  onConnect,
  onNodeClick,
  follow = false,
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
        {follow && (
          <FollowNodes ids={running.map((node) => node.id).join('\n')} />
        )}
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.5} />
        <Controls showInteractive={false} />
        <MiniMap
          // No room for a minimap on a phone-sized canvas (! beats xyflow's unlayered CSS)
          className="max-md:hidden!"
          pannable
          zoomable
          nodeStrokeWidth={0}
          nodeColor={(node) =>
            node.type === 'workflowNode'
              ? categoryAccent((node as WorkflowFlowNode).data.node.category)
              : 'var(--color-outline-variant)'
          }
        />
      </ReactFlow>
    </div>
  );
}
