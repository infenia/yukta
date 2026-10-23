// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { WorkflowProgress } from '../types/workflow';

export async function getWorkflowProgress(
  executionId: string
): Promise<WorkflowProgress> {
  return fetchApi<WorkflowProgress>(api`/api/executions/${executionId}`);
}
