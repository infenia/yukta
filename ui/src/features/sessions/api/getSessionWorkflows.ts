// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { WorkflowSummary } from '../types/session';

export async function getSessionWorkflows(
  sessionId: string
): Promise<WorkflowSummary[]> {
  return fetchApi<WorkflowSummary[]>(api`/api/sessions/${sessionId}/workflows`);
}
