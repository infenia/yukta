// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';

const post = (path: string, body?: unknown) =>
  fetchApi<unknown>(path, {
    method: 'POST',
    ...(body !== undefined && { body: JSON.stringify(body) }),
  });

/** Sends a message; the answer says whether it waits for the end of the agent's turn. */
export const sendConversationMessage = (
  conversationId: string,
  text: string,
  interrupt: boolean
) =>
  fetchApi<{ queued: boolean }>(
    api`/api/conversations/${conversationId}/messages`,
    {
      method: 'POST',
      body: JSON.stringify({ text, interrupt }),
    }
  );

/** Stops the agent's current turn. */
export const interruptConversation = (conversationId: string) =>
  post(api`/api/conversations/${conversationId}/interrupt`);

/** Ends a conversation; the node goes on with the status of the last turn. */
export const endConversation = (conversationId: string) =>
  post(api`/api/conversations/${conversationId}/end`, {});

/** Types keys into the terminal of an agent; the bytes go base64 encoded. */
export const sendTerminalInput = (
  conversationId: string,
  bytes: Uint8Array
) => {
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return post(api`/api/conversations/${conversationId}/terminal/input`, {
    data: btoa(binary),
  });
};

/** Tells the agent's terminal how big the view is. */
export const resizeTerminal = (
  conversationId: string,
  cols: number,
  rows: number
) =>
  post(api`/api/conversations/${conversationId}/terminal/resize`, {
    cols,
    rows,
  });
