// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { Ownership } from '../types';

/** Who owns the run level and each node of a workflow. */
export const getOwnership = (session: string, workflow: string) =>
  fetchApi<Ownership>(
    api`/api/supervision/ownership?session=${session}&workflow=${workflow}`
  );

/** Whether people may send one command here, who carries it, and why not. */
export interface Capability {
  allowed: boolean;
  /** The supervisor that carries it, or `default`. */
  via: string;
  reason: string | null;
}

/**
 * Where a command goes: a run (by its execution), one of its nodes, a workflow (to start it, by
 * session and workflow), or the server (none of them).
 */
export interface ControlTarget {
  execution?: string | null;
  node?: string | null;
  session?: string;
  workflow?: string;
}

/** The commands of a run, a node or the server, each with its capability. */
export const getSupervision = (target: ControlTarget) => {
  const query = new URLSearchParams();
  if (target.execution) query.set('execution', target.execution);
  if (target.node) query.set('node', target.node);
  if (target.session) query.set('session', target.session);
  if (target.workflow) query.set('workflow', target.workflow);
  const text = query.toString();
  return fetchApi<Record<string, Capability>>(
    `/api/supervision${text ? `?${text}` : ''}`
  );
};
