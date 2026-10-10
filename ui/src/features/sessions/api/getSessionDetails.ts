// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { SessionDetails } from '../types/session';

export async function getSessionDetails(
  sessionId: string
): Promise<SessionDetails> {
  return fetchApi<SessionDetails>(api`/api/sessions/${sessionId}`);
}
