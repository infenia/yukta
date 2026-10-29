// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

/** Deletes a session and its workflows; `force` cancels active runs first. Run history stays. */
export const deleteSession = (sessionId: string, force: boolean) =>
  fetchApi<undefined>(
    `${api`/api/sessions/${sessionId}`}${force ? '?force=true' : ''}`,
    {
      method: 'DELETE',
    }
  );

/** Deletes one workflow of a session; `force` cancels its active runs first. */
export const deleteWorkflow = (
  sessionId: string,
  workflowId: string,
  force: boolean
) =>
  fetchApi<undefined>(
    `${api`/api/sessions/${sessionId}/workflows/${workflowId}`}${force ? '?force=true' : ''}`,
    { method: 'DELETE' }
  );
