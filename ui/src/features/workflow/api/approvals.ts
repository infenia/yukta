// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

/** A node that waits for a signal (an approval or decision). */
export interface PendingApproval {
  executionId: string;
  workflowId: string;
  nodeId: string;
  name: string;
  since: string;
}

export const getPendingApprovals = (sessionId: string) =>
  fetchApi<PendingApproval[]>(api`/api/sessions/${sessionId}/approvals`);

/** Answers the wait with the decision (approve or reject). */
export const sendDecision = (
  approval: PendingApproval,
  decision: 'approve' | 'reject'
) =>
  fetchApi<unknown>(
    `${api`/api/executions/${approval.executionId}/nodes/${approval.nodeId}/signal`}` +
      `?name=${encodeURIComponent(approval.name)}&decision=${decision}`,
    { method: 'POST' }
  );
