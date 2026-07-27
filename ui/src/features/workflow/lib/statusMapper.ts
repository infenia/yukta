// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ExecStatusCategory, NodeUiStatus } from '../types/workflow';

const NODE_STATUS_MAP: Record<string, NodeUiStatus> = {
  SUCCESS: 'completed',
  COMPLETED: 'completed',
  RUNNING: 'running',
  NODE_RESUMED: 'running',
  NODE_STEPPED: 'running',
  NODE_UNSKIPPED: 'running',
  FAILURE: 'failed',
  FAILED: 'failed',
  ERROR: 'failed',
  PAUSED: 'paused',
  NODE_PAUSED: 'paused',
  NODE_SKIPPED: 'skipped',
  NODE_STOPPED: 'skipped',
  PENDING: 'pending',
};

const EXEC_STATUS_MAP: Record<string, ExecStatusCategory> = {
  RUNNING: 'running',
  NODE_RESUMED: 'running',
  NODE_STEPPED: 'running',
  COMPLETED: 'success',
  SUCCESS: 'success',
  FAILED: 'failure',
  FAILURE: 'failure',
  ERROR: 'failure',
  CANCELLED: 'stopped',
  WORKFLOW_STOPPED: 'stopped',
  NODE_STOPPED: 'stopped',
  PAUSED: 'paused',
  NODE_PAUSED: 'paused',
  PENDING: 'pending',
};

/** Terminal execution statuses, mirroring the backend LogManagementController vocabulary. */
const TERMINAL_EXEC_STATUSES = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'WORKFLOW_STOPPED',
  'NODE_STOPPED',
  'SUCCESS',
  'FAILURE',
  'ERROR',
]);

/** Map a raw backend task status onto a node rendering status; unknown values render as pending. */
export function toNodeUiStatus(raw: string | null | undefined): NodeUiStatus {
  if (!raw) {
    return 'pending';
  }
  return NODE_STATUS_MAP[raw] ?? 'pending';
}

/** Map a raw backend execution status onto a coarse bucket; unknown values map to 'unknown'. */
export function toExecStatusCategory(raw: string | null | undefined): ExecStatusCategory {
  if (!raw) {
    return 'unknown';
  }
  return EXEC_STATUS_MAP[raw] ?? 'unknown';
}

/** Whether a raw execution status means the execution has finished. */
export function isTerminalExecStatus(raw: string | null | undefined): boolean {
  return raw != null && TERMINAL_EXEC_STATUSES.has(raw);
}

/** Mockup-style node status label, e.g. "Done", "Active", "Waiting". */
export function toNodeStatusLabel(status: NodeUiStatus): string {
  const labels: Record<NodeUiStatus, string> = {
    completed: 'Done',
    running: 'Active',
    pending: 'Waiting',
    failed: 'Failed',
    paused: 'Paused',
    skipped: 'Skipped',
  };
  return labels[status];
}

/** Right-aligned task sequence label, e.g. "COMPLETED", "RUNNING", "PENDING". */
export function toTaskSequenceLabel(status: NodeUiStatus): string {
  const labels: Record<NodeUiStatus, string> = {
    completed: 'COMPLETED',
    running: 'RUNNING',
    pending: 'PENDING',
    failed: 'FAILED',
    paused: 'PAUSED',
    skipped: 'SKIPPED',
  };
  return labels[status];
}
