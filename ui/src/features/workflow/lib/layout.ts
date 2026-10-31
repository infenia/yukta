// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import dagre from '@dagrejs/dagre';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import { categoryAccent } from './categoryAccent';
import { aggregateStatus, groupNodes } from './groups';
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
  /** Hide the description line (the plugin page already shows it next to the preview). */
  hideDescription?: boolean;
  /** Hide the status line (a catalog preview has no run, so no status). */
  hideStatus?: boolean;
}

export type WorkflowFlowNode = Node<WorkflowNodeData, 'workflowNode'>;

/** A stage: its name, how many nodes it has, their aggregate status, and expand/collapse. */
export interface StageData extends Record<string, unknown> {
  stage: string;
  count: number;
  status: NodeUiStatus;
  onToggle?: (stage: string) => void;
}

/** A collapsed stage, drawn in place of its nodes. */
export type StageFlowNode = Node<StageData, 'stageNode'>;
/** The frame behind the nodes of an open stage. */
export type StageFrameNode = Node<StageData, 'stageFrame'>;
export type CanvasNode = WorkflowFlowNode | StageFlowNode | StageFrameNode;

/** Space around an open stage's nodes, and above them for its label. */
const FRAME_PAD = 16;
const FRAME_LABEL = 36;

/** Uniform canvas node size. */
export const DEFAULT_NODE_WIDTH = 240;
/** Room for a header, a one-line description, the status row and a progress bar. */
export const DEFAULT_NODE_HEIGHT = 108;
const NODE_SIZE = { width: DEFAULT_NODE_WIDTH, height: DEFAULT_NODE_HEIGHT };

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
  tasksByNode: Record<string, TaskProgress | undefined>,
  stageView?: { open: ReadonlySet<string>; onToggle: (stage: string) => void }
): { nodes: CanvasNode[]; edges: Edge[] } {
  const dagreGraph = new dagre.graphlib.Graph({ compound: true });
  dagreGraph.setGraph({ rankdir: 'LR', nodesep: 64, ranksep: 96 });
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const nodeById = new Map(graph.nodes.map((node) => [node.nodeId, node]));
  const statusByNode = new Map<string, NodeUiStatus>(
    graph.nodes.map((node) => [
      node.nodeId,
      toNodeUiStatus(tasksByNode[node.nodeId]?.status),
    ])
  );

  // Without a stage view every node is drawn; with one, a closed stage is one box
  const stages = stageView ? groupNodes(graph.nodes) : new Map();
  const stageByNode = new Map<string, string>();
  stages.forEach((members: WorkflowGraphNode[], stage: string) =>
    members.forEach((member) => stageByNode.set(member.nodeId, stage))
  );
  const opened = stageView?.open ?? new Set<string>();
  const isOpen = (stage: string) => opened.has(stage);
  const stageStatus = new Map<string, NodeUiStatus>(
    [...stages].map(([stage, members]: [string, WorkflowGraphNode[]]) => [
      stage,
      aggregateStatus(
        members.map((m) => statusByNode.get(m.nodeId) as NodeUiStatus)
      ),
    ])
  );
  /** The canvas node a graph node is drawn as: itself, or its closed stage. */
  const shownAs = (nodeId: string): string => {
    const stage = stageByNode.get(nodeId);
    return stage && !isOpen(stage) ? `stage:${stage}` : nodeId;
  };
  const statusOf = (shownId: string): NodeUiStatus =>
    shownId.startsWith('stage:') && !nodeById.has(shownId)
      ? (stageStatus.get(shownId.slice('stage:'.length)) as NodeUiStatus)
      : (statusByNode.get(shownId) as NodeUiStatus);

  graph.nodes.forEach((node) => {
    const shown = shownAs(node.nodeId);
    dagreGraph.setNode(shown, { ...NODE_SIZE }); // dagre writes x/y into the label
    const stage = stageByNode.get(node.nodeId);
    if (stage && shown === node.nodeId) {
      // an open stage is a dagre cluster, so its nodes stay together
      dagreGraph.setNode(`frame:${stage}`, {});
      dagreGraph.setParent(node.nodeId, `frame:${stage}`);
    }
  });
  const validEdges = graph.edges.filter(
    (edge) => nodeById.has(edge.source) && nodeById.has(edge.target)
  );
  validEdges.forEach((edge) => {
    const [source, target] = [shownAs(edge.source), shownAs(edge.target)];
    if (source !== target) dagreGraph.setEdge(source, target);
  });

  dagre.layout(dagreGraph);

  const nodes: CanvasNode[] = graph.nodes
    .filter((node) => shownAs(node.nodeId) === node.nodeId)
    .map((node) => {
      const { width, height } = NODE_SIZE;
      const position = dagreGraph.node(node.nodeId);
      // top port -> topmost target: the card lays ports out in this order
      const targetY = (port: string) =>
        Math.min(
          ...validEdges
            .filter(
              (e) =>
                e.source === node.nodeId &&
                resolveSourceHandle(node, e.sourcePort) === port
            )
            .map((e) => dagreGraph.node(shownAs(e.target)).y),
          Number.MAX_VALUE
        );
      const ordered = {
        ...node,
        outputPorts: [...node.outputPorts].sort(
          (a, b) => targetY(a) - targetY(b)
        ),
      };
      return {
        id: node.nodeId,
        type: 'workflowNode',
        position: { x: position.x - width / 2, y: position.y - height / 2 },
        width,
        height,
        draggable: false,
        connectable: false,
        data: {
          node: ordered,
          status: statusOf(node.nodeId),
          task: tasksByNode[node.nodeId],
        },
      } satisfies WorkflowFlowNode;
    });

  stages.forEach((members: WorkflowGraphNode[], stage: string) => {
    const data: StageData = {
      stage,
      count: members.length,
      status: stageStatus.get(stage) as NodeUiStatus,
      onToggle: stageView?.onToggle,
    };
    if (!isOpen(stage)) {
      const { x, y } = dagreGraph.node(`stage:${stage}`);
      nodes.push({
        id: `stage:${stage}`,
        type: 'stageNode',
        position: { x: x - NODE_SIZE.width / 2, y: y - NODE_SIZE.height / 2 },
        ...NODE_SIZE,
        draggable: false,
        connectable: false,
        data,
      });
      return;
    }
    // The frame hugs its nodes' boxes; dagre's own cluster size leaves no room for a label
    const boxes = members.map((m) => dagreGraph.node(m.nodeId));
    const left = Math.min(...boxes.map((b) => b.x)) - NODE_SIZE.width / 2;
    const top = Math.min(...boxes.map((b) => b.y)) - NODE_SIZE.height / 2;
    const right = Math.max(...boxes.map((b) => b.x)) + NODE_SIZE.width / 2;
    const bottom = Math.max(...boxes.map((b) => b.y)) + NODE_SIZE.height / 2;
    nodes.unshift({
      id: `frame:${stage}`,
      type: 'stageFrame',
      position: { x: left - FRAME_PAD, y: top - FRAME_PAD - FRAME_LABEL },
      width: right - left + 2 * FRAME_PAD,
      height: bottom - top + 2 * FRAME_PAD + FRAME_LABEL,
      zIndex: -1,
      draggable: false,
      connectable: false,
      selectable: false,
      data,
    });
  });

  // Edges inside a closed stage disappear; several into or out of it become one
  const seen = new Set<string>();
  const edges: Edge[] = validEdges
    .filter((edge) => {
      const [source, target] = [shownAs(edge.source), shownAs(edge.target)];
      const key = `${source}>${target}>${source === edge.source ? edge.sourcePort : ''}`;
      if (source === target || seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .map((edge) => {
      const source = shownAs(edge.source);
      const target = shownAs(edge.target);
      const sourceStatus = statusOf(source);
      const targetStatus = statusOf(target);
      const traversed = sourceStatus === 'completed';
      const sourceCategory = (nodeById.get(edge.source) as WorkflowGraphNode)
        .category;
      const stroke = traversed
        ? sourceCategory
          ? categoryAccent(sourceCategory)
          : 'var(--color-primary)'
        : 'var(--color-outline)';
      return {
        id: `${source}__${edge.sourcePort ?? 'all'}__${target}`,
        source,
        target,
        // a closed stage has one output
        sourceHandle:
          source === edge.source
            ? resolveSourceHandle(
                nodeById.get(edge.source) as WorkflowGraphNode,
                edge.sourcePort
              )
            : undefined,
        type: 'default',
        animated: traversed && targetStatus === 'running',
        markerEnd: {
          type: MarkerType.ArrowClosed,
          width: 16,
          height: 16,
          color: stroke,
        },
        style: { stroke, strokeWidth: traversed ? 2 : 1.5 },
      };
    });

  return { nodes, edges };
}
