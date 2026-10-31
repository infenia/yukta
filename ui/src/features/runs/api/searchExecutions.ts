// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import { timeBounds, type RunFilters } from '../lib/filters';

/** One run found by the search. */
export interface RunHit {
  sessionId: string;
  executionId: string;
  workflowId: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  /** Who or what started the run (for example `yk check`, `schedule`), when the server knows. */
  initiator?: string | null;
  /** How far the run got through its nodes, when the server knows. */
  nodes?: { done: number; total: number; failed: string | null } | null;
}

/** Runs running and queued now, and runs that failed in the last 24 hours. */
export interface RunCounts {
  running: number;
  queued: number;
  failed: number;
}

export const getRunCounts = () => fetchApi<RunCounts>('/api/executions/counts');

/** The most runs the server returns for one search. */
export const MAX_RUNS = 1000;

/** The newest `limit` runs that match, across all sessions (or one). */
export function searchExecutions(
  filters: RunFilters,
  limit: number,
  now = Date.now()
): Promise<RunHit[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  const wanted: Record<string, string> = {
    sessionId: filters.session,
    workflowId: filters.workflow,
    status: filters.status,
    text: filters.text,
    ...timeBounds(filters, now),
  };
  for (const [key, value] of Object.entries(wanted)) {
    if (value) params.set(key, value);
  }
  return fetchApi<RunHit[]>(`/api/executions?${params}`);
}
