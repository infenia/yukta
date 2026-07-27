// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type {
  ExecutionSummary,
  LogEntry,
  TaskProgress,
  WorkflowGraph,
  WorkflowGraphNode,
  WorkflowProgress,
} from '@/features/workflow/types/workflow';

export function createMockGraphNode(overrides: Partial<WorkflowGraphNode> = {}): WorkflowGraphNode {
  return {
    nodeId: 'data-ingress',
    type: 'ingress-v1',
    category: 'TRIGGER',
    description: 'Ingest raw data',
    uiDesign: null,
    outputPorts: ['default'],
    ...overrides,
  };
}

/** The mockup's diamond DAG: trigger fans out to two processors that fan into a terminal. */
export function createMockGraph(overrides: Partial<WorkflowGraph> = {}): WorkflowGraph {
  return {
    workflowId: 'wf-982-xk-11',
    description: 'Supply_Chain_Optimizer_V2',
    nodes: [
      createMockGraphNode(),
      createMockGraphNode({
        nodeId: 'vectorize-batch',
        type: 'vector-engine-v2',
        category: 'PROCESSOR',
        description: 'Vectorize batches',
        uiDesign: { html: '<div class="metrics">{{nodeId}}</div>', width: 200, height: 40 },
        outputPorts: ['default', 'error'],
      }),
      createMockGraphNode({
        nodeId: 'anomaly-detection',
        type: 'ml-filter-v1',
        category: 'PROCESSOR',
        description: 'Detect anomalies',
      }),
      createMockGraphNode({
        nodeId: 'sink-storage',
        type: 'storage-sink-v1',
        category: 'TERMINAL',
        description: 'Persist results',
        outputPorts: [],
      }),
    ],
    edges: [
      { source: 'data-ingress', target: 'vectorize-batch', sourcePort: null },
      { source: 'data-ingress', target: 'anomaly-detection', sourcePort: null },
      { source: 'vectorize-batch', target: 'sink-storage', sourcePort: 'default' },
      { source: 'anomaly-detection', target: 'sink-storage', sourcePort: null },
    ],
    topologicalOrder: ['data-ingress', 'vectorize-batch', 'anomaly-detection', 'sink-storage'],
    ...overrides,
  };
}

export function createMockExecution(overrides: Partial<ExecutionSummary> = {}): ExecutionSummary {
  return {
    executionId: 'exec-091-qp-55',
    workflowId: 'wf-982-xk-11',
    status: 'RUNNING',
    startTime: '2026-07-26T14:22:01',
    endTime: null,
    ...overrides,
  };
}

export function createMockTask(overrides: Partial<TaskProgress> = {}): TaskProgress {
  return {
    nodeId: 'data-ingress',
    module: 'ingress-v1',
    status: 'SUCCESS',
    startTime: '2026-07-26T14:22:01',
    endTime: '2026-07-26T14:22:10',
    ...overrides,
  };
}

/** Progress matching the mockup: ingress done, vectorize running, the rest pending. */
export function createMockProgress(overrides: Partial<WorkflowProgress> = {}): WorkflowProgress {
  return {
    executionId: 'exec-091-qp-55',
    sessionId: 'SESSION_A92_XP_2024',
    workflowId: 'wf-982-xk-11',
    status: 'RUNNING',
    tasks: [
      createMockTask(),
      createMockTask({
        nodeId: 'vectorize-batch',
        module: 'vector-engine-v2',
        status: 'RUNNING',
        startTime: '2026-07-26T14:22:11',
        endTime: null,
        metadata: { progress: 42 },
      }),
      createMockTask({
        nodeId: 'anomaly-detection',
        module: 'ml-filter-v1',
        status: 'PENDING',
        startTime: null,
        endTime: null,
      }),
      createMockTask({
        nodeId: 'sink-storage',
        module: 'storage-sink-v1',
        status: 'PENDING',
        startTime: null,
        endTime: null,
      }),
    ],
    startTime: '2026-07-26T14:22:01',
    endTime: null,
    ...overrides,
  };
}

export function createMockLogEntry(overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    executionId: 'exec-091-qp-55',
    pluginId: 'vectorize-batch',
    pluginName: 'vector-engine-v2',
    stream: 'STDOUT',
    message: 'Processing batch 1/45...',
    level: 'INFO',
    timestamp: '2026-07-26T14:22:15Z',
    ...overrides,
  };
}
