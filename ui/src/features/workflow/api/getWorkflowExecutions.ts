// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { WorkflowExecutions } from '../types/workflow';

export async function getWorkflowExecutions(
  sessionId: string,
  workflowId: string
): Promise<WorkflowExecutions> {
  return fetchApi<WorkflowExecutions>(
    `/api/sessions/${sessionId}/workflows/${workflowId}/executions`
  );
}
