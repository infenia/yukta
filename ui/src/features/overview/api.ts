// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { InputRequest } from '@/features/workflow/api/inputs';

/** The newest `limit` input requests that wait for an answer, across all sessions the key may see. */
export const getOpenInputs = (limit = 50) =>
  fetchApi<InputRequest[]>(`/api/inputs?limit=${limit}`);
