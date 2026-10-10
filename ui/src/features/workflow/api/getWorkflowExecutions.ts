// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { ExecutionSummary } from '../types/workflow';

export async function getWorkflowExecutions(
  sessionId: string,
  workflowId: string
): Promise<ExecutionSummary[]> {
  return fetchApi<ExecutionSummary[]>(
    api`/api/sessions/${sessionId}/workflows/${workflowId}/executions`
  );
}
