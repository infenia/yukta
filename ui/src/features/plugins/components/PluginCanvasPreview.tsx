// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { ReactFlow, ReactFlowProvider } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { WorkflowNodeCard } from '@/features/workflow/components/nodes/WorkflowNodeCard';
import {
  DEFAULT_NODE_HEIGHT,
  DEFAULT_NODE_WIDTH,
  type WorkflowFlowNode,
} from '@/features/workflow/lib/layout';
import type { PluginDetails } from '../types';

const NODE_TYPES = { workflowNode: WorkflowNodeCard };

/** Same chrome height as layout.ts (header + status row around the plugin HTML). */
const NODE_CHROME_HEIGHT = 56;

/** Renders the real canvas node card for a plugin, so the preview can't drift from the canvas. */
export function PluginCanvasPreview({ plugin }: { plugin: PluginDetails }) {
  const { uiDesign } = plugin;
  const width = uiDesign ? Math.max(uiDesign.width, DEFAULT_NODE_WIDTH) : DEFAULT_NODE_WIDTH;
  const height = uiDesign ? uiDesign.height + NODE_CHROME_HEIGHT : DEFAULT_NODE_HEIGHT;
  const node: WorkflowFlowNode = {
    id: plugin.type,
    type: 'workflowNode',
    position: { x: 0, y: 0 },
    width,
    height,
    data: { status: 'pending', node: { ...plugin, nodeId: plugin.type } },
  };

  return (
    <div className="h-64 border border-outline-variant" data-testid="plugin-canvas-preview">
      <ReactFlowProvider>
        <ReactFlow
          key={plugin.type}
          nodes={[node]}
          edges={[]}
          nodeTypes={NODE_TYPES}
          fitView
          fitViewOptions={{ maxZoom: 1.5, padding: 0.4 }}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          panOnDrag={false}
          zoomOnScroll={false}
          zoomOnPinch={false}
          zoomOnDoubleClick={false}
          preventScrolling={false}
          proOptions={{ hideAttribution: true }}
        />
      </ReactFlowProvider>
    </div>
  );
}
