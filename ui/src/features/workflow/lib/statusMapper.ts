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
  STOPPED: 'skipped',
  CANCELLED: 'skipped',
  SKIPPED: 'skipped',
  UPSTREAM_FAILED: 'skipped',
  TIMED_OUT: 'failed',
  ABORTED: 'failed',
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
  // Core writes NODE_STOPPED as the workflow status when one node is stopped; the run goes on.
  NODE_STOPPED: 'running',
  STOPPED: 'stopped',
  TIMED_OUT: 'failure',
  ABORTED: 'failure',
  PAUSED: 'paused',
  NODE_PAUSED: 'paused',
  // Control state while a stop drains in-flight work; the run is not terminal yet.
  STOPPING: 'stopped',
  PENDING: 'pending',
  QUEUED: 'pending',
};

/** Terminal execution statuses, mirroring the backend LogManagementController vocabulary. */
const TERMINAL_EXEC_STATUSES = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'WORKFLOW_STOPPED',
  'STOPPED',
  'TIMED_OUT',
  'ABORTED',
  'SKIPPED',
  'UPSTREAM_FAILED',
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
export function toExecStatusCategory(
  raw: string | null | undefined
): ExecStatusCategory {
  if (!raw) {
    return 'unknown';
  }
  return EXEC_STATUS_MAP[raw] ?? 'unknown';
}

/** Whether a raw execution status means the execution has finished. */
export function isTerminalExecStatus(raw: string | null | undefined): boolean {
  return raw != null && TERMINAL_EXEC_STATUSES.has(raw);
}

/** Whether a raw execution status is QUEUED: the run waits for a free execution slot. */
export function isQueuedExecStatus(raw: string | null | undefined): boolean {
  return raw === 'QUEUED';
}

/** Whether a raw execution status is ABORTED: a server crash or restart interrupted the run. */
export function isAbortedExecStatus(raw: string | null | undefined): boolean {
  return raw === 'ABORTED';
}

/** Status to display for an execution: a live pause or stop control state overrides a non-terminal status. */
export function toDisplayExecStatus(
  status: string | null | undefined,
  controlState: string | null | undefined
): string | null {
  const shown =
    controlState === 'PAUSED' ||
    controlState === 'NODE_PAUSED' ||
    controlState === 'STOPPING';
  return shown && !isTerminalExecStatus(status)
    ? controlState
    : (status ?? null);
}

/** Label of a node rendering status, in the design system's status vocabulary. */
export function toNodeStatusLabel(status: NodeUiStatus): string {
  const labels: Record<NodeUiStatus, string> = {
    completed: 'Succeeded',
    running: 'Running',
    pending: 'Waiting',
    failed: 'Failed',
    paused: 'Paused',
    skipped: 'Skipped',
  };
  return labels[status];
}

/** Colour family of a status: a design-system token (muted is muted-foreground). */
export type StatusTone =
  'muted' | 'primary' | 'attention' | 'success' | 'destructive';

/** How a status is shown: label, tone, and an icon, or a live dot while work is happening. */
export interface StatusDisplay {
  label: string;
  tone: StatusTone;
  /** Material Symbol name; null shows a live dot instead. */
  icon: string | null;
  /** The node never ran: drawn with a dashed outline. */
  dashed?: boolean;
}

const RUNNING_DISPLAY: StatusDisplay = {
  label: 'Running',
  tone: 'primary',
  icon: null,
};
const PAUSED_DISPLAY: StatusDisplay = {
  label: 'Paused',
  tone: 'attention',
  icon: 'pause',
};
const SUCCEEDED_DISPLAY: StatusDisplay = {
  label: 'Succeeded',
  tone: 'success',
  icon: 'check_circle',
};
const FAILED_DISPLAY: StatusDisplay = {
  label: 'Failed',
  tone: 'destructive',
  icon: 'error',
};
const STOPPED_DISPLAY: StatusDisplay = {
  label: 'Stopped',
  tone: 'muted',
  icon: 'stop_circle',
};

/** The one status vocabulary (design system "Run status" table), keyed by raw backend value. */
const STATUS_DISPLAY: Record<string, StatusDisplay> = {
  PENDING: { label: 'Waiting', tone: 'muted', icon: 'hourglass_empty' },
  QUEUED: { label: 'Queued', tone: 'muted', icon: 'schedule' },
  RUNNING: RUNNING_DISPLAY,
  NODE_RESUMED: RUNNING_DISPLAY,
  NODE_STEPPED: RUNNING_DISPLAY,
  NODE_UNSKIPPED: RUNNING_DISPLAY,
  PAUSED: PAUSED_DISPLAY,
  NODE_PAUSED: PAUSED_DISPLAY,
  STOPPING: { label: 'Stopping', tone: 'attention', icon: null },
  SUCCESS: SUCCEEDED_DISPLAY,
  COMPLETED: SUCCEEDED_DISPLAY,
  FAILURE: FAILED_DISPLAY,
  FAILED: FAILED_DISPLAY,
  ERROR: FAILED_DISPLAY,
  TIMED_OUT: { label: 'Timed out', tone: 'destructive', icon: 'timer_off' },
  ABORTED: { label: 'Interrupted', tone: 'destructive', icon: 'power_off' },
  STOPPED: STOPPED_DISPLAY,
  CANCELLED: STOPPED_DISPLAY,
  WORKFLOW_STOPPED: STOPPED_DISPLAY,
  NODE_STOPPED: STOPPED_DISPLAY,
  SKIPPED: { label: 'Skipped', tone: 'muted', icon: 'skip_next', dashed: true },
  NODE_SKIPPED: {
    label: 'Skipped',
    tone: 'muted',
    icon: 'skip_next',
    dashed: true,
  },
  UPSTREAM_FAILED: {
    label: 'Not reached',
    tone: 'muted',
    icon: 'block',
    dashed: true,
  },
};

const NOT_RUN_DISPLAY: StatusDisplay = {
  label: 'Not run',
  tone: 'muted',
  icon: 'schedule',
};

/**
 * Display of a raw status. `run` scope reads node control events the way core writes them as a
 * workflow status: a stopped or skipped node leaves the run going. Unknown values show humanized.
 */
export function toStatusDisplay(
  raw: string | null | undefined,
  scope: 'run' | 'node' = 'run'
): StatusDisplay {
  if (!raw) {
    return NOT_RUN_DISPLAY;
  }
  if (scope === 'run' && (raw === 'NODE_STOPPED' || raw === 'NODE_SKIPPED')) {
    return RUNNING_DISPLAY;
  }
  const known = STATUS_DISPLAY[raw];
  if (known) {
    return known;
  }
  const words = raw.replace(/_/g, ' ').toLowerCase();
  return {
    label: words.charAt(0).toUpperCase() + words.slice(1),
    tone: 'muted',
    icon: 'help',
  };
}
