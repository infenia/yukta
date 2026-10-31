// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { PendingApproval } from '@/features/workflow/api/approvals';

/** A pending approval of any session. */
export interface SessionApproval extends PendingApproval {
  sessionId: string;
}

/** The oldest `limit` nodes that wait for a signal, across all sessions the key may see. */
export const getAllApprovals = (limit = 50) =>
  fetchApi<SessionApproval[]>(`/api/approvals?limit=${limit}`);
