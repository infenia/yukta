// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type {
  AttachmentView,
  Decision,
  SupervisorDraft,
  SupervisorView,
  ValidationResult,
} from '../types';

const json = (method: string, body: unknown): RequestInit => ({
  method,
  body: JSON.stringify(body),
});

export const getSupervisors = () =>
  fetchApi<SupervisorView[]>('/api/supervisors');

export const getSupervisor = (id: string) =>
  fetchApi<SupervisorView>(api`/api/supervisors/${id}`);

export const createSupervisor = (draft: SupervisorDraft) =>
  fetchApi<SupervisorView>('/api/supervisors', json('POST', draft));

export const replaceSupervisor = (draft: SupervisorDraft & { id: string }) =>
  fetchApi<SupervisorView>(
    api`/api/supervisors/${draft.id}`,
    json('PUT', draft)
  );

export const deleteSupervisor = (id: string) =>
  fetchApi<unknown>(api`/api/supervisors/${id}`, { method: 'DELETE' });

/** Checks a draft without saving it: always answers, look at `valid`. */
export const validateSupervisor = (draft: SupervisorDraft) =>
  fetchApi<ValidationResult>('/api/supervisors/validate', json('POST', draft));

export interface AttachmentFilter {
  supervisor?: string;
  session?: string;
  workflow?: string;
  source?: string;
}

export const getAttachments = (filter: AttachmentFilter = {}) => {
  if (filter.supervisor) {
    return fetchApi<AttachmentView[]>(
      api`/api/supervisors/${filter.supervisor}/attachments`
    );
  }
  const query = new URLSearchParams();
  if (filter.session) query.set('session', filter.session);
  if (filter.workflow) query.set('workflow', filter.workflow);
  if (filter.source) query.set('source', filter.source);
  const text = query.toString();
  return fetchApi<AttachmentView[]>(
    `/api/supervisors/attachments${text ? `?${text}` : ''}`
  );
};

export const attachSupervisor = (attachment: {
  supervisorId: string;
  scope: string;
  enabled: boolean;
}) =>
  fetchApi<AttachmentView>(
    '/api/supervisors/attachments',
    json('POST', attachment)
  );

export const setAttachmentEnabled = (id: string, enabled: boolean) =>
  fetchApi<AttachmentView>(
    api`/api/supervisors/attachments/${id}`,
    json('PATCH', { enabled })
  );

export const detachAttachment = (id: string) =>
  fetchApi<unknown>(api`/api/supervisors/attachments/${id}`, {
    method: 'DELETE',
  });

export const getSupervisorDecisions = (supervisor?: string, limit = 50) => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (supervisor) query.set('supervisor', supervisor);
  return fetchApi<Decision[]>(`/api/supervisors/decisions?${query}`);
};

/** The node ids of a workflow, for the node picker. */
export const getWorkflowNodeIds = async (session: string, workflow: string) =>
  (
    await fetchApi<{ nodes: { nodeId: string }[] }>(
      api`/api/sessions/${session}/workflows/${workflow}`
    )
  ).nodes.map((n) => n.nodeId);

/** What the control bus reports about each running supervisor (and control-plane plugin). */
export interface RunningSupervisor {
  id: string;
  kind: 'CONTROL_PLANE' | 'SUPERVISOR';
}

export const getRunningSupervisors = () =>
  fetchApi<RunningSupervisor[]>('/api/supervisors/status');
