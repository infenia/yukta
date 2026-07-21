// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { SessionListItems } from '../types/session';

export async function getSessionSummaries(): Promise<SessionListItems> {
  return fetchApi<SessionListItems>('/api/sessions/summaries');
}
