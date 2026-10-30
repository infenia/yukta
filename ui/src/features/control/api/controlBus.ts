// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

/** A node on the control bus. */
export interface BusNode {
  workflowId: string;
  nodeId: string;
}

/** The last heartbeat of one node of one run (`executionId` is empty without a run). */
export interface NodeHeartbeat extends BusNode {
  executionId: string;
  timestamp: string;
}

/** Every node's last heartbeat, in one request. */
export const getHeartbeats = () =>
  fetchApi<NodeHeartbeat[]>('/api/control/heartbeats');

/** The most keys the server accepts in a command. */
export const MAX_COMMAND_KEYS = 100;

export const sendCommand = (
  workflowId: string,
  nodeId: string,
  command: Record<string, unknown>
) =>
  fetchApi<{ payload: unknown }>(
    api`/api/control/workflows/${workflowId}/nodes/${nodeId}/command`,
    { method: 'POST', body: JSON.stringify(command) }
  );

/** Why `text` cannot be sent as a command, or the command itself. */
export function parseCommand(
  text: string
): { command: Record<string, unknown> } | { error: string } {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return { error: 'The command is not valid JSON.' };
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { error: 'The command must be a JSON object.' };
  }
  const keys = Object.keys(value).length;
  if (keys === 0) return { error: 'The command must not be empty.' };
  if (keys > MAX_COMMAND_KEYS) {
    return {
      error: `The command has ${keys} keys; at most ${MAX_COMMAND_KEYS} are allowed.`,
    };
  }
  return { command: value as Record<string, unknown> };
}
