// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api } from '@/lib/apiClient';
import { authHeaders } from '@/lib/apiKey';
import type { ResultEvent } from '../types/workflow';

/** What the JSON form of the results endpoint returns (it is not wrapped in an ApiResponse). */
export interface ExecutionResults {
  executionId: string;
  complete: boolean;
  status: string | null;
  truncated: boolean;
  nextCursor: number;
  events: ResultEvent[];
}

/** Every result event after `afterCursor`; the server sends them in one response. */
export async function getExecutionResults(
  sessionId: string,
  executionId: string,
  afterCursor = 0
): Promise<ExecutionResults> {
  const response = await fetch(
    `${api`/api/sessions/${sessionId}/executions/${executionId}/results`}?afterCursor=${afterCursor}`,
    { headers: { Accept: 'application/json', ...authHeaders() } }
  );
  if (!response.ok) {
    throw new Error(`Failed to load results (${response.status})`);
  }
  return (await response.json()) as ExecutionResults;
}
