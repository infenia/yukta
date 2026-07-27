// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export type PluginCategory = 'TRIGGER' | 'PROCESSOR' | 'TERMINAL';

export interface UiDesign {
  html: string;
  width: number;
  height: number;
}

export interface WorkflowGraphNode {
  nodeId: string;
  type: string;
  category: PluginCategory | null;
  description: string | null;
  uiDesign: UiDesign | null;
  outputPorts: string[];
}

export interface WorkflowGraphEdge {
  source: string;
  target: string;
  sourcePort: string | null;
}

export interface WorkflowGraph {
  workflowId: string;
  description: string;
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
  topologicalOrder: string[];
}

export interface ExecutionSummary {
  executionId: string;
  workflowId: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
}

export interface WorkflowExecutions {
  executions: ExecutionSummary[];
}

export interface TaskProgress {
  nodeId: string;
  module: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  metadata?: Record<string, unknown>;
}

export interface WorkflowProgress {
  executionId: string;
  sessionId: string;
  workflowId: string;
  status: string;
  tasks: TaskProgress[];
  startTime: string | null;
  endTime: string | null;
}

export interface LogEntry {
  executionId: string;
  pluginId: string;
  pluginName: string;
  stream: string | null;
  message: string;
  level: string | null;
  timestamp: string;
}

/** Node rendering status derived from a raw backend task status. */
export type NodeUiStatus = 'completed' | 'running' | 'pending' | 'failed' | 'paused' | 'skipped';

/** Coarse execution status bucket derived from a raw backend execution status. */
export type ExecStatusCategory =
  | 'running'
  | 'success'
  | 'failure'
  | 'stopped'
  | 'paused'
  | 'pending'
  | 'unknown';
