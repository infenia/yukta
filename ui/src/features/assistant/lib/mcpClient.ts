// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { authHeaders } from '@/lib/apiKey';
import type { ToolSpec } from '../types';
import { sseData } from './sse';

let nextId = 1;
let cached: Promise<ToolSpec[]> | null = null;

/** What to tell the person when Yukta refuses a call, in plain words. */
function refusal(status: number): string {
  if (status === 401)
    return 'Yukta needs your API key. Add it, then try again.';
  if (status === 403) return 'Your API key is not allowed to do that.';
  if (status === 429) return 'Too many tries. Wait a minute, then try again.';
  return 'Yukta could not do that right now. Try again in a moment.';
}

/** One JSON-RPC call to Yukta's own MCP endpoint (same origin, the same key as the REST API). */
async function rpc<T>(
  method: string,
  params: unknown,
  signal?: AbortSignal
): Promise<T> {
  const res = await fetch('/mcp', {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      ...authHeaders(),
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: nextId++, method, params }),
  });
  if (!res.ok) {
    throw new Error(refusal(res.status));
  }
  let text = await res.text();
  if (res.headers.get('content-type')?.includes('text/event-stream')) {
    const stream = new Response(text).body as ReadableStream<Uint8Array>;
    text = '';
    for await (const data of sseData(stream)) text = data;
  }
  const reply = JSON.parse(text);
  if (reply.error) throw new Error(reply.error.message);
  return reply.result as T;
}

/** The tools Yukta offers, fetched once per page load. */
export function listTools(): Promise<ToolSpec[]> {
  cached ??= rpc<{ tools: ToolSpec[] }>('tools/list', {}).then(
    (result) => result.tools,
    (error) => {
      cached = null;
      throw error;
    }
  );
  return cached;
}

/** Forgets the fetched list, so the next {@link listTools} asks again. */
export function resetTools(): void {
  cached = null;
}

/** Runs one tool; a failure the tool reports comes back as `isError`, not as a throw. */
export async function callTool(
  name: string,
  args: Record<string, unknown>,
  signal?: AbortSignal
): Promise<{ text: string; isError: boolean }> {
  const result = await rpc<{
    content?: { type: string; text?: string }[];
    isError?: boolean;
  }>('tools/call', { name, arguments: args }, signal);
  return {
    text: (result.content ?? []).map((part) => part.text ?? '').join('\n'),
    isError: result.isError === true,
  };
}
