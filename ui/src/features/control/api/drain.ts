// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

/** Whether the scheduler refuses new starts so the server can be swapped, and what is left. */
export interface DrainState {
  draining: boolean;
  running: number;
  queued: number;
  idle: boolean;
}

export const getDrain = () => fetchApi<DrainState>('/api/admin/drain');

/** Starts (`true`) or ends (`false`) the drain. */
export const setDrain = (on: boolean) =>
  fetchApi<DrainState>('/api/admin/drain', { method: on ? 'POST' : 'DELETE' });
