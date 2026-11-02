// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

/** A node of any session that waits for an answer (an input request or a signal). */
export interface SessionApproval {
  sessionId: string;
  executionId: string;
  workflowId: string;
  nodeId: string;
  name: string;
  since: string;
}

/** The oldest `limit` nodes that wait for a signal, across all sessions the key may see. */
export const getAllApprovals = (limit = 50) =>
  fetchApi<SessionApproval[]>(`/api/approvals?limit=${limit}`);
