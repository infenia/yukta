// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { SupervisorTypeDetails, SupervisorTypeSummary } from '../types';

export const getSupervisorTypes = () =>
  fetchApi<SupervisorTypeSummary[]>('/api/supervisor-types');

export const getSupervisorType = (type: string) =>
  fetchApi<SupervisorTypeDetails>(api`/api/supervisor-types/${type}`);
