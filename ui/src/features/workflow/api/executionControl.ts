// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

export type StopMode = 'DRAIN' | 'CANCEL' | 'KILL';
export type RunAction = 'pause' | 'resume' | 'stop' | 'restart';
export type NodeAction =
  | 'pause'
  | 'resume'
  | 'stop'
  | 'skip'
  | 'retry'
  | 'step'
  | 'step/enable'
  | 'step/disable';

interface StartResponse {
  executionId: string;
}

const post = <T>(path: string) => fetchApi<T>(path, { method: 'POST' });

export function startWorkflow(
  sessionId: string,
  workflowId: string,
  payload: Record<string, unknown>,
  idempotencyKey: string
): Promise<StartResponse> {
  return fetchApi<StartResponse>('/api/workflow/start', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ sessionId, workflowId, payload }),
  });
}

/** Stops every run of the workflow with the given mode (DRAIN lets in-flight work finish). */
export function stopWorkflow(
  sessionId: string,
  workflowId: string,
  mode: StopMode
) {
  return post<{ executionIds: string[] }>(
    `${api`/api/workflow/${sessionId}/${workflowId}/stop`}?mode=${mode}`
  );
}

/** `mode` only means something for `stop`: DRAIN (the server's default), CANCEL or KILL. */
export function runAction(
  executionId: string,
  action: RunAction,
  mode?: StopMode
): Promise<StartResponse> {
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/${action}`}${mode ? `?mode=${mode}` : ''}`
  );
}

/** Changes the priority of a QUEUED run; higher runs first. */
export function changePriority(
  executionId: string,
  priority: number
): Promise<StartResponse> {
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/priority`}?priority=${priority}`
  );
}

/** Continues an ABORTED run as a new run; finished nodes are replayed from their recorded output. */
export function recoverExecution(executionId: string): Promise<StartResponse> {
  return post<StartResponse>(api`/api/executions/${executionId}/recover`);
}

/** Pauses a node when `field` of its message equals `equals`, for example payload.status = error. */
export function setBreakpoint(
  executionId: string,
  nodeId: string,
  field: string,
  equals: string
): Promise<StartResponse> {
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/nodes/${nodeId}/breakpoint`}?field=${encodeURIComponent(field)}&equals=${encodeURIComponent(equals)}`
  );
}

export function clearBreakpoint(
  executionId: string,
  nodeId: string
): Promise<StartResponse> {
  return fetchApi<StartResponse>(
    api`/api/executions/${executionId}/nodes/${nodeId}/breakpoint`,
    {
      method: 'DELETE',
    }
  );
}

export function restartFromNode(
  executionId: string,
  nodeId: string
): Promise<StartResponse> {
  return post<StartResponse>(
    api`/api/executions/${executionId}/restart/${nodeId}`
  );
}

/** Stops one node; `immediate` skips draining and `reason` lands in the control audit. */
export function stopNode(
  executionId: string,
  nodeId: string,
  options: { immediate?: boolean; reason?: string } = {}
): Promise<StartResponse> {
  const query = new URLSearchParams();
  if (options.immediate) query.set('immediate', 'true');
  if (options.reason) query.set('reason', options.reason);
  const suffix = query.size ? `?${query}` : '';
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/nodes/${nodeId}`}/stop${suffix}`
  );
}

/** Takes a node back out of the skipped state before it runs. */
export function unskipNode(
  executionId: string,
  nodeId: string
): Promise<StartResponse> {
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/nodes/${nodeId}`}/skip?skip=false`
  );
}

export function nodeAction(
  executionId: string,
  nodeId: string,
  action: NodeAction
) {
  // action is a closed union (step/enable has a slash), so it is appended unencoded.
  return post<StartResponse>(
    `${api`/api/executions/${executionId}/nodes/${nodeId}`}/${action}`
  );
}
