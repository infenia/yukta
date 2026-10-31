// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type {
  DraftWorkflow,
  SessionDraft,
  TemplateDetail,
  TemplateSummary,
  ValidationResult,
} from './types';

/** The complete session (tag values and every workflow): saving replaces it, so nothing may be missing. */
export function getSessionDraft(sessionId: string): Promise<SessionDraft> {
  return fetchApi<SessionDraft>(api`/api/sessions/${sessionId}/config`);
}

export function saveSession(session: SessionDraft): Promise<unknown> {
  return fetchApi<unknown>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify(session),
  });
}

export function validateWorkflow(
  workflow: DraftWorkflow
): Promise<ValidationResult> {
  return fetchApi<ValidationResult>('/api/sessions/validate', {
    method: 'POST',
    body: JSON.stringify(workflow),
  });
}

/** What the server finds at a project path (only an ADMIN key may ask). */
export function checkProjectPath(
  projectPath: string
): Promise<{ exists: boolean; directory: boolean }> {
  return fetchApi('/api/sessions/validate/project-path', {
    method: 'POST',
    body: JSON.stringify({ projectPath }),
  });
}

export function listTemplates(): Promise<TemplateSummary[]> {
  return fetchApi<TemplateSummary[]>('/api/templates');
}

export function getTemplate(id: string): Promise<TemplateDetail> {
  return fetchApi<TemplateDetail>(api`/api/templates/${id}`);
}
