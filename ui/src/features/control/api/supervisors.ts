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
  scope: 'GLOBAL' | 'SESSION' | 'WORKFLOW' | 'SECTION';
  scopeSessionId: string | null;
  scopeWorkflowId: string | null;
  /** The node selectors of a `SECTION` scope (node ids and `<prefix>.*`), otherwise null. */
  scopeNodes?: string[] | null;
  /** Commands the arbiter dropped in favour of another supervisor. */
  arbitrated?: number;
  /** Commands merged into an equal one from another supervisor. */
  coalesced?: number;
  /** Commands blocked because the supervisor kept turning a target around. */
  flappingBlocked?: number;
}

/** A reusable supervisor setup. Values under secret-looking keys come back as `***`. */
export interface SupervisorProfile {
  id: string;
  type: string;
  settings: Record<string, unknown>;
  dryRun: boolean;
}

/**
 * A profile attached to a scope: `global`, `session:<id>`, `workflow:<id>`, `workflow:<s>/<w>`, or a
 * section of a workflow, `workflow:<s>/<w>//<selector>,<selector>`.
 */
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
  outcome:
    | 'applied'
    | 'rejected'
    | 'dry-run'
    | 'rate-limited'
    | 'forbidden'
    | 'out-of-scope'
    | 'arbitrated'
    | 'coalesced'
    | 'flapping-blocked'
    | 'not-owner'
    | 'awaiting-approval'
    // a newer server may name more; the UI shows them in a neutral colour
    | (string & {});
  command: string;
  executionId: string;
  reason: string | null;
}

const BASE = '/api/supervisors';
const json = (method: string, body: unknown): RequestInit => ({
  method,
  body: JSON.stringify(body),
});

export const getSupervisors = () => fetchApi<Supervisor[]>(`${BASE}/status`);
/** The configured supervisors, the ones the server configures itself left out. */
export const getProfiles = async () =>
  (await fetchApi<(SupervisorProfile & { system?: boolean })[]>(BASE)).filter(
    (s) => !s.system
  );
/** The attachments, those of the server's own supervisors left out. */
export const getBindings = async () =>
  (
    await fetchApi<
      (Omit<SupervisorBinding, 'profileId'> & {
        supervisorId: string;
        source: string;
      })[]
    >(`${BASE}/attachments`)
  )
    .filter((a) => a.source !== 'SYSTEM')
    .map(({ supervisorId, ...rest }) => ({ ...rest, profileId: supervisorId }));
export const getDecisions = (limit = 30) =>
  fetchApi<Decision[]>(`${BASE}/decisions?limit=${limit}`);

export const replaceProfile = (profile: SupervisorProfile) =>
  fetchApi<SupervisorProfile>(
    `${BASE}${api`/${profile.id}`}`,
    json('PUT', profile)
  );
export const deleteProfile = (id: string) =>
  fetchApi<unknown>(`${BASE}${api`/${id}`}`, { method: 'DELETE' });

export const attach = ({
  profileId,
  ...binding
}: Omit<SupervisorBinding, 'id'> & { id?: string }) =>
  fetchApi<unknown>(
    `${BASE}/attachments`,
    json('POST', { ...binding, supervisorId: profileId })
  );
export const detach = (id: string) =>
  fetchApi<unknown>(`${BASE}/attachments${api`/${id}`}`, { method: 'DELETE' });

/** Separates the workflow from the node selectors in a section scope. */
const SECTION = '//';

/** A scope as a person reads it. */
export function scopeLabel(scope: string): string {
  if (scope === 'global') return 'Global';
  const [kind, rest] = [
    scope.split(':')[0],
    scope.slice(scope.indexOf(':') + 1),
  ];
  if (kind === 'session') return `Session ${rest}`;
  const at = rest.indexOf(SECTION);
  return at < 0
    ? `Workflow ${rest}`
    : `Section ${rest.slice(0, at)}: ${rest
        .slice(at + SECTION.length)
        .split(',')
        .join(', ')}`;
}

/**
 * The node selectors typed as text: separated by commas or spaces, blanks dropped. A selector is a
 * node id or `<prefix>.*`.
 */
export function parseSelectors(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((selector) => selector.trim())
    .filter((selector) => selector !== '');
}

/**
 * The scope text for a kind and its ids, or null when an id is missing. A section also needs at
 * least one node selector.
 */
export function buildScope(
  kind: 'global' | 'session' | 'workflow' | 'section',
  sessionId: string,
  workflowId: string,
  nodes = ''
): string | null {
  if (kind === 'global') return 'global';
  if (kind === 'session') return sessionId ? `session:${sessionId}` : null;
  if (!workflowId) return null;
  const workflow = sessionId
    ? `workflow:${sessionId}/${workflowId}`
    : `workflow:${workflowId}`;
  if (kind === 'workflow') return workflow;
  const selectors = parseSelectors(nodes);
  return selectors.length === 0
    ? null
    : `${workflow}${SECTION}${selectors.join(',')}`;
}
