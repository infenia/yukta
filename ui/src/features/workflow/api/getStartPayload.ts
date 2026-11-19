// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

export interface StartPayload {
  /** Secrets masked, the engine's `yukta.*` entries left out; null once the log dropped the run. */
  payload: Record<string, unknown> | null;
}

/** What a run was started with: the values `${start.…}` in a node's config reads. */
export const getStartPayload = (executionId: string) =>
  fetchApi<StartPayload>(api`/api/executions/${executionId}/start-payload`);
