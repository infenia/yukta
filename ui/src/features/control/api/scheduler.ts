// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';

/** The execution limits in force and the slots in use. 0 means unlimited. */
export interface SchedulerLimits {
  maxConcurrent: number;
  maxPerWorkflow: number;
  running: number;
  queued: number;
  queueCapacity: number;
}

export const getLimits = () =>
  fetchApi<SchedulerLimits>('/api/scheduler/limits');

export const setLimits = (maxConcurrent: number, maxPerWorkflow: number) =>
  fetchApi<unknown>(
    `/api/scheduler/limits?maxConcurrent=${maxConcurrent}&maxPerWorkflow=${maxPerWorkflow}`,
    { method: 'POST' }
  );

/** A limit as the person reads it. */
export const limitText = (limit: number) =>
  limit === 0 ? 'Unlimited' : String(limit);

/** The non-negative whole number in `text`, or null when it is not one. */
export function parseLimit(text: string): number | null {
  return /^\d+$/.test(text.trim()) ? Number(text.trim()) : null;
}
