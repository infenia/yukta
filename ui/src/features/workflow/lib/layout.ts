// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import dagre from '@dagrejs/dagre';
import type { Edge, Node } from '@xyflow/react';
import { toNodeUiStatus } from './statusMapper';
import type {
  NodeUiStatus,
  TaskProgress,
  WorkflowGraph,
  WorkflowGraphNode,
} from '../types/workflow';

/** Data payload carried by every canvas node placeholder. */
export interface WorkflowNodeData extends Record<string, unknown> {
  node: WorkflowGraphNode;
  status: NodeUiStatus;
  task?: TaskProgress;
}

export type WorkflowFlowNode = Node<WorkflowNodeData, 'workflowNode'>;

/** Default placeholder size when a plugin declares no UI design. */
export const DEFAULT_NODE_WIDTH = 240;
export const DEFAULT_NODE_HEIGHT = 88;

/** Vertical chrome (header + status row) added around plugin-provided details HTML. */
const NODE_CHROME_HEIGHT = 56;

function nodeSize(node: WorkflowGraphNode): { width: number; height: number } {
  if (!node.uiDesign) {
    return { width: DEFAULT_NODE_WIDTH, height: DEFAULT_NODE_HEIGHT };
  }
  return {
    width: Math.max(node.uiDesign.width, DEFAULT_NODE_WIDTH),
    height: node.uiDesign.height + NODE_CHROME_HEIGHT,
  };
}

function resolveSourceHandle(
  sourceNode: WorkflowGraphNode,
  sourcePort: string | null
): string | undefined {
  const ports = sourceNode.outputPorts;
  if (ports.length === 0) {
    return undefined;
  }
  if (sourcePort && ports.includes(sourcePort)) {
    return sourcePort;
  }
  return ports[0];
}

/**
 * Compute canvas positions for the workflow DAG and derive per-node/edge rendering state.
 *
 * Pure function: given the backend graph and the task progress keyed by nodeId, returns
 * ready-to-render React Flow nodes and edges laid out left-to-right with dagre.
 */
export function layoutGraph(
  graph: WorkflowGraph,
  tasksByNode: Record<string, TaskProgress | undefined>
): { nodes: WorkflowFlowNode[]; edges: Edge[] } {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setGraph({ rankdir: 'LR', nodesep: 64, ranksep: 96 });
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const nodeById = new Map(graph.nodes.map((node) => [node.nodeId, node]));

  graph.nodes.forEach((node) => {
    dagreGraph.setNode(node.nodeId, nodeSize(node));
  });
  graph.edges.forEach((edge) => {
    if (nodeById.has(edge.source) && nodeById.has(edge.target)) {
      dagreGraph.setEdge(edge.source, edge.target);
    }
  });

  dagre.layout(dagreGraph);

  const statusByNode = new Map<string, NodeUiStatus>(
    graph.nodes.map((node) => [node.nodeId, toNodeUiStatus(tasksByNode[node.nodeId]?.status)])
  );
  // Every graph node has an entry, so lookups by known node id cannot miss
  const statusOf = (nodeId: string): NodeUiStatus => statusByNode.get(nodeId) as NodeUiStatus;

  const nodes: WorkflowFlowNode[] = graph.nodes.map((node) => {
    const { width, height } = nodeSize(node);
    const position = dagreGraph.node(node.nodeId);
    return {
      id: node.nodeId,
      type: 'workflowNode',
      position: { x: position.x - width / 2, y: position.y - height / 2 },
      width,
      height,
      draggable: false,
      connectable: false,
      data: {
        node,
        status: statusOf(node.nodeId),
        task: tasksByNode[node.nodeId],
      },
    };
  });

  const edges: Edge[] = graph.edges
    .filter((edge) => nodeById.has(edge.source) && nodeById.has(edge.target))
    .map((edge) => {
      const sourceStatus = statusOf(edge.source);
      const targetStatus = statusOf(edge.target);
      const traversed = sourceStatus === 'completed';
      return {
        id: `${edge.source}__${edge.sourcePort ?? 'all'}__${edge.target}`,
        source: edge.source,
        target: edge.target,
        sourceHandle: resolveSourceHandle(nodeById.get(edge.source) as WorkflowGraphNode, edge.sourcePort),
        type: 'default',
        animated: traversed && targetStatus === 'running',
        style: traversed
          ? { stroke: 'var(--color-primary)', strokeWidth: 2 }
          : { stroke: 'var(--color-outline)', strokeWidth: 1.5 },
      };
    });

  return { nodes, edges };
}
