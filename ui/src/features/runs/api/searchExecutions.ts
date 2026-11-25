// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi, fetchApiPage } from '@/lib/apiClient';
import { statusList, timeBounds, type RunFilters } from '../lib/filters';

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

/** One page of runs, and how many runs match in all. */
export interface RunPage {
  runs: RunHit[];
  total: number;
}

/** Page `page` (from 0) of the runs that match, newest first, across all sessions (or one). */
export async function searchRunPage(
  filters: RunFilters,
  page: number,
  pageSize: number,
  now = Date.now()
): Promise<RunPage> {
  const params = new URLSearchParams({
    limit: String(pageSize),
    offset: String(page * pageSize),
  });
  const wanted: Record<string, string> = {
    sessionId: filters.session,
    workflowId: filters.workflow,
    status: statusList(filters.status).join(','),
    text: filters.text,
    ...timeBounds(filters, now),
  };
  for (const [key, value] of Object.entries(wanted)) {
    if (value) params.set(key, value);
  }
  const { data, total } = await fetchApiPage<RunHit[]>(
    `/api/executions?${params}`
  );
  return { runs: data, total: total ?? data.length };
}

/** The newest `limit` runs that match (one page, no total). */
export async function searchExecutions(
  filters: RunFilters,
  limit: number,
  now = Date.now()
): Promise<RunHit[]> {
  return (await searchRunPage(filters, 0, limit, now)).runs;
}
