// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type {
  SupervisorPreset,
  SupervisorTypeDetails,
  SupervisorTypeSummary,
} from '../types';

export const getSupervisorTypes = () =>
  fetchApi<SupervisorTypeSummary[]>('/api/supervisor-engines');

export const getSupervisorType = (type: string) =>
  fetchApi<SupervisorTypeDetails>(api`/api/supervisor-engines/${type}`);

/** The ids of the supervisors wired to a decision maker. */
export const getSupervisorTypeUsage = (type: string) =>
  fetchApi<string[]>(api`/api/supervisor-engines/${type}/usage`);

/** The ready-made settings of a decision maker. */
export const getSupervisorTypePresets = (type: string) =>
  fetchApi<SupervisorPreset[]>(api`/api/supervisor-engines/${type}/presets`);
