// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowProgress } from '../types/workflow';
import { toStatusDisplay } from './statusMapper';

/** One status transition of a run or one of its nodes. */
export interface RunEvent {
  time: string;
  /** The node, or null for the run itself. */
  nodeId: string | null;
  /** "Started", or the status it ended in ("Succeeded", "Failed", …). */
  label: string;
}

/**
 * The run's status transitions, oldest first, from the start and end times it already carries:
 * so a finished run has them too, not only one watched live.
 */
export function runEvents(progress: WorkflowProgress): RunEvent[] {
  const events: RunEvent[] = [];
  const add = (time: string | null, nodeId: string | null, label: string) => {
    if (time) events.push({ time, nodeId, label });
  };
  add(progress.startTime, null, 'Started');
  for (const task of progress.tasks) {
    add(task.startTime, task.nodeId, 'Started');
    add(task.endTime, task.nodeId, toStatusDisplay(task.status, 'node').label);
  }
  add(progress.endTime, null, toStatusDisplay(progress.status).label);
  // a stable sort keeps the run's own start first and its end last at equal times
  return events.sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
}
