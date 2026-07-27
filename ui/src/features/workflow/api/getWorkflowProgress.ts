// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { WorkflowProgress } from '../types/workflow';

export async function getWorkflowProgress(
  sessionId: string,
  executionId: string
): Promise<WorkflowProgress> {
  return fetchApi<WorkflowProgress>(`/api/workflow/${sessionId}/status/${executionId}`);
}
