// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowProgress } from '../types/workflow';
import { toStatusDisplay, type StatusDisplay } from './statusMapper';

/** One status transition of a run or one of its nodes. */
export interface RunEvent {
  time: string;
  /** The node, or null for the run itself. */
  nodeId: string | null;
  /** "Started", or the status it ended in ("Succeeded", "Failed", …). */
  label: string;
  /** How the label is coloured: the status's tone, primary for a start. */
  tone: StatusDisplay['tone'];
}

/**
 * The run's status transitions, oldest first, from the start and end times it already carries:
 * so a finished run has them too, not only one watched live.
 */
export function runEvents(progress: WorkflowProgress): RunEvent[] {
  const events: RunEvent[] = [];
  const add = (
    time: string | null,
    nodeId: string | null,
    label: string,
    tone: RunEvent['tone']
  ) => {
    if (time) events.push({ time, nodeId, label, tone });
  };
  const end = (time: string | null, nodeId: string | null, raw: string) => {
    const { label, tone } = toStatusDisplay(raw, nodeId ? 'node' : 'run');
    add(time, nodeId, label, tone);
  };
  add(progress.startTime, null, 'Started', 'primary');
  for (const task of progress.tasks) {
    add(task.startTime, task.nodeId, 'Started', 'primary');
    end(task.endTime, task.nodeId, task.status);
  }
  end(progress.endTime, null, progress.status);
  // a stable sort keeps the run's own start first and its end last at equal times
  return events.sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
}
