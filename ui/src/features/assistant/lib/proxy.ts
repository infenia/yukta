// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { authHeaders } from '@/lib/apiKey';
import { sseData } from './sse';

export type Provider = 'anthropic' | 'openai';

/** What a provider answered when it refused: its own message, else the text, else the status. */
async function failure(res: Response): Promise<string> {
  const text = await res.text();
  try {
    const body = JSON.parse(text);
    return body.error?.message ?? body.message ?? text;
  } catch {
    return text || res.statusText;
  }
}

/**
 * Sends a streaming request to the provider through Yukta's relay (the page may only talk to its
 * own origin) and yields the `data:` payload of each event. The key goes in a header and is not
 * kept by the server.
 */
export async function* streamProxy(
  provider: Provider,
  body: unknown,
  config: { key: string; baseUrl?: string },
  signal: AbortSignal
): AsyncGenerator<string> {
  const res = await fetch(`/api/assistant/proxy/${provider}`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      'X-Assistant-Key': config.key,
      ...(config.baseUrl ? { 'X-Assistant-Base-Url': config.baseUrl } : {}),
      ...authHeaders(),
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await failure(res));
  yield* sseData(res.body as ReadableStream<Uint8Array>);
}
