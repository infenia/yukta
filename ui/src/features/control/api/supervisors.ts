// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

/** A supervisor or control-plane plugin running on the control bus. */
export interface Supervisor {
  id: string;
  kind: 'CONTROL_PLANE' | 'SUPERVISOR';
  type: string;
  order: number;
  dryRun: boolean;
  maxCommandsPerSecond: number;
  applied: number;
  rejected: number;
  recorded: number;
  rateLimited: number;
  scope: 'GLOBAL' | 'SESSION' | 'WORKFLOW';
  scopeSessionId: string | null;
  scopeWorkflowId: string | null;
}

/** A reusable supervisor setup. Values under secret-looking keys come back as `***`. */
export interface SupervisorProfile {
  id: string;
  type: string;
  settings: Record<string, unknown>;
  dryRun: boolean;
}

/** A profile attached to a scope: `global`, `session:<id>`, `workflow:<id>` or `workflow:<s>/<w>`. */
export interface SupervisorBinding {
  id: string;
  profileId: string;
  scope: string;
  enabled: boolean;
}

/** What became of one command a supervisor emitted. */
export interface Decision {
  time: string;
  supervisorId: string;
  outcome: 'applied' | 'rejected' | 'dry-run' | 'rate-limited';
  command: string;
  executionId: string;
  reason: string | null;
}

const BASE = '/api/supervisors';
const json = (method: string, body: unknown): RequestInit => ({
  method,
  body: JSON.stringify(body),
});

export const getSupervisors = () => fetchApi<Supervisor[]>(BASE);
export const getTypes = () => fetchApi<string[]>(`${BASE}/types`);
export const getProfiles = () =>
  fetchApi<SupervisorProfile[]>(`${BASE}/profiles`);
export const getBindings = () =>
  fetchApi<SupervisorBinding[]>(`${BASE}/bindings`);
export const getDecisions = (limit = 30) =>
  fetchApi<Decision[]>(`${BASE}/decisions?limit=${limit}`);

export const createProfile = (profile: SupervisorProfile) =>
  fetchApi<SupervisorProfile>(`${BASE}/profiles`, json('POST', profile));
export const replaceProfile = (profile: SupervisorProfile) =>
  fetchApi<SupervisorProfile>(
    `${BASE}${api`/profiles/${profile.id}`}`,
    json('PUT', profile)
  );
export const deleteProfile = (id: string) =>
  fetchApi<unknown>(`${BASE}${api`/profiles/${id}`}`, { method: 'DELETE' });

export const attach = (
  binding: Omit<SupervisorBinding, 'id'> & { id?: string }
) => fetchApi<SupervisorBinding>(`${BASE}/bindings`, json('POST', binding));
export const detach = (id: string) =>
  fetchApi<unknown>(`${BASE}${api`/bindings/${id}`}`, { method: 'DELETE' });

/** A scope as a person reads it. */
export function scopeLabel(scope: string): string {
  if (scope === 'global') return 'Global';
  const [kind, rest] = [
    scope.split(':')[0],
    scope.slice(scope.indexOf(':') + 1),
  ];
  return kind === 'session' ? `Session ${rest}` : `Workflow ${rest}`;
}

/**
 * Whether a binding's scope covers a session, and a workflow of it when given: a global scope
 * covers everything, `workflow:<id>` any session's workflow of that id.
 */
export function scopeApplies(
  scope: string,
  sessionId: string,
  workflowId?: string
): boolean {
  if (scope === 'global') return true;
  const [kind, rest] = [
    scope.split(':')[0],
    scope.slice(scope.indexOf(':') + 1),
  ];
  if (kind === 'session') return rest === sessionId;
  if (kind !== 'workflow' || workflowId === undefined) return false;
  const [s, w] = rest.includes('/') ? rest.split('/') : [undefined, rest];
  return w === workflowId && (s === undefined || s === sessionId);
}

/** The scope text for a kind and its ids, or null when an id is missing. */
export function buildScope(
  kind: 'global' | 'session' | 'workflow',
  sessionId: string,
  workflowId: string
): string | null {
  if (kind === 'global') return 'global';
  if (kind === 'session') return sessionId ? `session:${sessionId}` : null;
  if (!workflowId) return null;
  return sessionId
    ? `workflow:${sessionId}/${workflowId}`
    : `workflow:${workflowId}`;
}

/** Why `text` is not a settings object, or the object. */
export function parseSettings(
  text: string
): { settings: Record<string, unknown> } | { error: string } {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return { error: 'The settings are not valid JSON.' };
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { error: 'The settings must be a JSON object.' };
  }
  return { settings: value as Record<string, unknown> };
}
