// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { WorkflowGraph } from '../types/workflow';

export async function getWorkflowGraph(
  sessionId: string,
  workflowId: string
): Promise<WorkflowGraph> {
  return fetchApi<WorkflowGraph>(`/api/sessions/${sessionId}/workflows/${workflowId}/graph`);
}
