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
      api`/api/supervisors/${filter.supervisor}/assignments`
    );
  }
  const query = new URLSearchParams();
  if (filter.session) query.set('session', filter.session);
  if (filter.workflow) query.set('workflow', filter.workflow);
  if (filter.source) query.set('source', filter.source);
  const text = query.toString();
  return fetchApi<AttachmentView[]>(
    `/api/supervisors/assignments${text ? `?${text}` : ''}`
  );
};

/** One thing a supervisor owns, as the assignments endpoint takes it. */
interface AssignmentBody {
  target: string;
  enabled: boolean;
  locked: boolean;
}

/** What a supervisor owns by hand now: the assignments that a PUT replaces. */
const manualAssignments = async (supervisorId: string) =>
  (await getAttachments({ supervisor: supervisorId }))
    .filter((a) => a.source === 'MANUAL')
    .map<AssignmentBody & { id: string }>((a) => ({
      id: a.id,
      target: a.scope,
      enabled: a.enabled,
      locked: a.locked ?? false,
    }));

const putAssignments = (supervisorId: string, wanted: AssignmentBody[]) =>
  fetchApi<AttachmentView[]>(
    api`/api/supervisors/${supervisorId}/assignments`,
    json(
      'PUT',
      wanted.map(({ target, enabled, locked }) => ({ target, enabled, locked }))
    )
  );

/** The supervisor of a manual assignment, by its id. */
const ownerOf = async (id: string) => {
  const found = (await getAttachments({ source: 'MANUAL' })).find(
    (a) => a.id === id
  );
  if (!found) throw new Error(`Assignment ${id} not found`);
  return found.supervisorId;
};

/** Adds one target to what a supervisor owns; the server checks the whole list. */
export const attachSupervisor = async (attachment: {
  supervisorId: string;
  scope: string;
  enabled: boolean;
}) => {
  const current = await manualAssignments(attachment.supervisorId);
  const saved = await putAssignments(attachment.supervisorId, [
    ...current.filter((a) => a.target !== attachment.scope),
    { target: attachment.scope, enabled: attachment.enabled, locked: false },
  ]);
  return saved.find((a) => a.scope === attachment.scope) ?? saved[0];
};

export const setAttachmentEnabled = async (id: string, enabled: boolean) => {
  const supervisorId = await ownerOf(id);
  const current = await manualAssignments(supervisorId);
  const saved = await putAssignments(
    supervisorId,
    current.map((a) => (a.id === id ? { ...a, enabled } : a))
  );
  return saved.find((a) => a.id === id);
};

export const detachAttachment = async (id: string) => {
  const supervisorId = await ownerOf(id);
  const current = await manualAssignments(supervisorId);
  return putAssignments(
    supervisorId,
    current.filter((a) => a.id !== id)
  );
};

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
