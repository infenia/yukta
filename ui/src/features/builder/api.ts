// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { DraftWorkflow, SessionDraft, ValidationResult } from './types';

/** The complete session (tag values and every workflow): saving replaces it, so nothing may be missing. */
export function getSessionDraft(sessionId: string): Promise<SessionDraft> {
  return fetchApi<SessionDraft>(api`/api/sessions/${sessionId}/config`);
}

export function saveSession(session: SessionDraft): Promise<unknown> {
  return fetchApi<unknown>('/api/sessions', { method: 'POST', body: JSON.stringify(session) });
}

export function validateWorkflow(workflow: DraftWorkflow): Promise<ValidationResult> {
  return fetchApi<ValidationResult>('/api/sessions/validate', {
    method: 'POST',
    body: JSON.stringify(workflow),
  });
}
