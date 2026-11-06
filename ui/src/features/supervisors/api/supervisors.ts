// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { SupervisorView } from '../types';

export const getSupervisors = () =>
  fetchApi<SupervisorView[]>('/api/supervisors');
