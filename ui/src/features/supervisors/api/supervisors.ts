// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type {
  AttachmentView,
  Decision,
  SupervisorDraft,
  SupervisorView,
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

export const setAttachmentEnabled = async (id: string, enabled: boolean) => {
  const supervisorId = await ownerOf(id);
  const current = await manualAssignments(supervisorId);
  const saved = await putAssignments(
    supervisorId,
    current.map((a) => (a.id === id ? { ...a, enabled } : a))
  );
  return saved.find((a) => a.id === id);
};

export const getSupervisorDecisions = (supervisor?: string, limit = 50) => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (supervisor) query.set('supervisor', supervisor);
  return fetchApi<Decision[]>(`/api/supervisors/decisions?${query}`);
};

/** What the control bus reports about each running supervisor (and control-plane plugin). */
export interface RunningSupervisor {
  id: string;
  kind: 'CONTROL_PLANE' | 'SUPERVISOR';
}

export const getRunningSupervisors = () =>
  fetchApi<RunningSupervisor[]>('/api/supervisors/status');

/** One kept version of a supervisor, newest first. */
export interface SupervisorVersion {
  version: number;
  savedAt: string;
  supervisor: SupervisorView;
}

export const getSupervisorVersions = (id: string) =>
  fetchApi<SupervisorVersion[]>(api`/api/supervisors/${id}/versions`);

/** Saves a kept version again as the newest. */
export const rollbackSupervisor = ({
  id,
  version,
}: {
  id: string;
  version: number;
}) =>
  fetchApi<SupervisorView>(
    api`/api/supervisors/${id}/rollback?version=${version}`,
    {
      method: 'POST',
    }
  );
