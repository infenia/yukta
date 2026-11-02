// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api } from '@/lib/apiClient';
import type { ControlAnnotation } from '@/features/metrics/api';

/** What a marker says: the command, who sent it, and why it was refused. */
export function annotationLabel(mark: ControlAnnotation): string {
  const by = mark.actor ? ` by ${mark.actor}` : '';
  if (mark.applied) return `${mark.command}${by}`;
  return `${mark.command}${by} (refused${mark.reason ? `: ${mark.reason}` : ''})`;
}

/**
 * Where a marker leads: the run it steered, or the control audit for a command that names no run
 * (a change of the limits, say).
 */
export function annotationTarget(mark: ControlAnnotation): string {
  if (mark.sessionId && mark.workflowId) {
    const run = api`/sessions/${mark.sessionId}/workflow/${mark.workflowId}`;
    return mark.executionId
      ? `${run}?exec=${encodeURIComponent(mark.executionId)}`
      : run;
  }
  return mark.executionId
    ? `/control?tab=audit&execution=${encodeURIComponent(mark.executionId)}`
    : '/control?tab=audit';
}
