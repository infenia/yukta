// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

/** One saved version of a workflow definition. */
export interface WorkflowVersion {
  id: string;
  savedAt: string;
  /** What the version is for, if somebody wrote it down. */
  note?: string | null;
}

/** What changed between a saved version and the current definition. */
export interface WorkflowDiff {
  descriptionChanged: boolean;
  addedNodes: string[];
  removedNodes: string[];
  changedNodes: string[];
  addedEdges: string[];
  removedEdges: string[];
}

const base = (sessionId: string, workflowId: string) =>
  api`/api/sessions/${sessionId}/workflows/${workflowId}`;

export const getVersions = (sessionId: string, workflowId: string) =>
  fetchApi<WorkflowVersion[]>(`${base(sessionId, workflowId)}/versions`);

/** Compares the version with the current definition. */
export const getDiff = (
  sessionId: string,
  workflowId: string,
  version: string
) =>
  fetchApi<WorkflowDiff>(
    `${base(sessionId, workflowId)}/versions/diff?from=${encodeURIComponent(version)}`
  );

export const rollback = (
  sessionId: string,
  workflowId: string,
  version: string
) =>
  fetchApi<unknown>(
    `${base(sessionId, workflowId)}/rollback?version=${encodeURIComponent(version)}`,
    { method: 'POST' }
  );

/** Writes the note of a version; an empty text clears it. */
export const saveNote = (
  sessionId: string,
  workflowId: string,
  version: string,
  note: string
) =>
  fetchApi<unknown>(
    `${base(sessionId, workflowId)}/versions/note?version=${encodeURIComponent(version)}&note=${encodeURIComponent(note)}`,
    { method: 'POST' }
  );
