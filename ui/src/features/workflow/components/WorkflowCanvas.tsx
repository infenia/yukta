// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo } from 'react';
import { Background, BackgroundVariant, Controls, MiniMap, ReactFlow } from '@xyflow/react';
import { categoryAccent } from '../lib/categoryAccent';
import type { WorkflowFlowNode } from '../lib/layout';
import '@xyflow/react/dist/style.css';
import { WorkflowNodeCard } from './nodes/WorkflowNodeCard';
import { layoutGraph } from '../lib/layout';
import type { TaskProgress, WorkflowGraph } from '../types/workflow';

const NODE_TYPES = { workflowNode: WorkflowNodeCard };

/** The workflow DAG canvas: pan/zoom viewport of backend-driven node placeholders. */
export function WorkflowCanvas({
  graph,
  tasksByNode,
}: {
  graph: WorkflowGraph;
  tasksByNode: Record<string, TaskProgress | undefined>;
}) {
  const { nodes, edges } = useMemo(() => layoutGraph(graph, tasksByNode), [graph, tasksByNode]);

  return (
    <div className="size-full" data-testid="workflow-canvas">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        // fitView stops at minZoom; xyflow's default (0.5) leaves a wide graph cut off on a
        // phone-width canvas, so let it zoom out further
        minZoom={0.2}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.5} />
        <Controls showInteractive={false} />
        <MiniMap
          // No room for a minimap on a phone-sized canvas (! beats xyflow's unlayered CSS)
          className="max-md:hidden!"
          pannable
          zoomable
          nodeStrokeWidth={0}
          nodeColor={(node) => categoryAccent((node as WorkflowFlowNode).data.node.category)}
        />
      </ReactFlow>
    </div>
  );
}
