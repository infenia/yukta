// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { vi } from 'vitest';
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

/**
 * A fake of the browser's built-in model session. `answers` are what constrained `prompt` calls
 * return, one after the other; `chunks` is what `promptStreaming` sends. `clone` gives a probe
 * with its own `prompt` and `destroy`, so the planning step can be told from the answer.
 */
export function nanoSession(answers: string[] = [], chunks: string[] = ['ok']) {
  const queue = [...answers];
  const probe = {
    prompt: vi.fn(() => Promise.resolve(queue.shift() as string)),
    destroy: vi.fn(),
  };
  return {
    probe,
    prompt: vi.fn(),
    promptStreaming: vi.fn(
      () =>
        new ReadableStream<string>({
          start(controller) {
            for (const chunk of chunks) controller.enqueue(chunk);
            controller.close();
          },
        })
    ),
    clone: vi.fn(() => Promise.resolve(probe)),
    contextUsage: 12,
    contextWindow: 100,
    destroy: vi.fn(),
  };
}
