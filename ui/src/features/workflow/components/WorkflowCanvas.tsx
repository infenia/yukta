// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo } from 'react';
import { ReactFlow } from '@xyflow/react';
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
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        proOptions={{ hideAttribution: true }}
      />
    </div>
  );
}
