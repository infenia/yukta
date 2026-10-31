// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toNodeUiStatus } from './statusMapper';
import type { NodeUiStatus, TaskProgress } from '../types/workflow';

/** The bar colour of a node status, shared by the summary bar and the run timeline. */
export const BAR_COLOR: Record<NodeUiStatus, string> = {
  failed: 'bg-destructive',
  running: 'bg-primary',
  paused: 'bg-attention',
  completed: 'bg-success',
  skipped: 'bg-outline',
  pending: 'bg-transparent',
};

export interface TimelineRow {
  nodeId: string;
  module: string;
  status: string;
  uiStatus: NodeUiStatus;
  startMs: number;
  endMs: number;
  /** Where the bar starts and how wide it is, in percent of the run's span. */
  left: number;
  width: number;
}

/** A bar never thins out to nothing, so a node that ran for a blink can still be seen. */
const MIN_WIDTH_PCT = 0.5;

/**
 * One row per node that has started, in start order, laid out on the span from the first start to
 * the last end; a node still running ends `now`. Nodes that have not started have no bar.
 */
export function buildTimeline(tasks: TaskProgress[], now: number) {
  const started = tasks.flatMap((task) => {
    const startMs = task.startTime ? Date.parse(task.startTime) : NaN;
    if (Number.isNaN(startMs)) {
      return [];
    }
    const parsedEnd = task.endTime ? Date.parse(task.endTime) : NaN;
    const endMs = Number.isNaN(parsedEnd) ? Math.max(now, startMs) : parsedEnd;
    return [{ task, startMs, endMs: Math.max(endMs, startMs) }];
  });
  const from = Math.min(...started.map((s) => s.startMs));
  const to = Math.max(...started.map((s) => s.endMs));
  const span = Math.max(to - from, 1);
  const rows: TimelineRow[] = started
    .sort((a, b) => a.startMs - b.startMs)
    .map(({ task, startMs, endMs }) => ({
      nodeId: task.nodeId,
      module: task.module,
      status: task.status,
      uiStatus: toNodeUiStatus(task.status),
      startMs,
      endMs,
      left: ((startMs - from) / span) * 100,
      width: Math.max(((endMs - startMs) / span) * 100, MIN_WIDTH_PCT),
    }));
  return { rows, from, to };
}
