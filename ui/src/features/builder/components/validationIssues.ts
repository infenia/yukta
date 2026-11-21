// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ValidationResult } from '../types';

export type InspectorTab =
  'config' | 'connections' | 'policy' | 'json' | 'test';

export interface Problem {
  /** The node it is about; null for the workflow as a whole. */
  nodeId: string | null;
  message: string;
  /** The inspector tab where it is fixed: engine settings are under Policy. */
  tab: InspectorTab;
}

/** The supervisors' problems show with the supervisors, not here. */
const isSupervisor = (message: string) => message.startsWith('assignments[');

/**
 * The problems that stop a workflow, one each: from `details` (node and place), else from
 * `message` for a server that sends no details.
 */
export function problemsOf(result: ValidationResult | null): Problem[] {
  if (!result || result.valid) return [];
  const details = result.details ?? [];
  if (details.length === 0) {
    return result.message && !isSupervisor(result.message)
      ? [{ nodeId: null, message: result.message, tab: 'config' }]
      : [];
  }
  return details
    .filter((d) => !isSupervisor(d.message))
    .map((d) => ({
      nodeId: d.nodeId,
      message: d.message,
      tab: d.path?.startsWith('engine') ? 'policy' : 'config',
    }));
}

/** Errors and warnings per node, for the badges on the canvas. */
export function issueCounts(
  result: ValidationResult | null
): Record<string, { errors: number; warnings: number }> {
  const counts: Record<string, { errors: number; warnings: number }> = {};
  const of = (nodeId: string) =>
    (counts[nodeId] ??= { errors: 0, warnings: 0 });
  problemsOf(result).forEach((p) => {
    if (p.nodeId) of(p.nodeId).errors += 1;
  });
  result?.warnings?.forEach((w) => {
    of(w.nodeId).warnings += 1;
  });
  return counts;
}
