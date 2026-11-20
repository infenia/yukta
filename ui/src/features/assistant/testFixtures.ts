// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ChatModel, ModelEvent, Msg } from './types';

/** A streaming Response whose body arrives as the given text chunks. */
export function streamResponse(
  chunks: string[],
  init: ResponseInit = { headers: { 'content-type': 'text/event-stream' } }
): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }),
    init
  );
}

/** A server-sent-events Response with one event per payload. */
export const sseResponse = (payloads: unknown[]): Response =>
  streamResponse(
    payloads.map(
      (p) => `data: ${typeof p === 'string' ? p : JSON.stringify(p)}\n\n`
    )
  );

export async function collect<T>(iterable: AsyncIterable<T>): Promise<T[]> {
  const out: T[] = [];
  for await (const item of iterable) out.push(item);
  return out;
}

export const text = (delta: string): ModelEvent => ({ type: 'text', delta });

export const call = (
  id: string,
  name: string,
  args: Record<string, unknown> = {}
): ModelEvent => ({ type: 'call', call: { id, name, args } });

/** A model that plays one scripted turn per request; an Error in a turn is thrown at that point. */
export function scriptedModel(turns: (ModelEvent | Error)[][]): ChatModel & {
  sent: Msg[][];
} {
  const sent: Msg[][] = [];
  return {
    label: 'fake · your key',
    sent,
    async *stream(_system, history) {
      sent.push(history);
      for (const item of turns[sent.length - 1]) {
        if (item instanceof Error) throw item;
        yield item;
      }
    },
  };
}
