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

/** What the server finds at a working directory (only an ADMIN key may ask). */
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

export interface TestRunRequest {
  workflow: DraftWorkflow;
  /** Run only this node and the nodes before it. */
  untilNodeId?: string;
  /** With `untilNodeId`: run that node alone, fed `payload`. */
  stepOnly?: boolean;
  payload?: Record<string, unknown>;
}

/** Runs an unsaved draft as a test run; the run is invisible to history and search. */
export function startTestRun(
  sessionId: string,
  request: TestRunRequest
): Promise<{ executionId: string; workflowId: string }> {
  return fetchApi(api`/api/sessions/${sessionId}/test-runs`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}
