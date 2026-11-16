// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, afterEach } from 'vitest';
import { KeyedEventSource } from '../keyedEventSource';

describe('KeyedEventSource', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends Last-Event-ID when resuming, and reports the id of each frame', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('id: 7\ndata: {"a":1}\n\n', {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const received: string[] = [];
    const source = new KeyedEventSource('/api/stream', '6');
    source.onmessage = (event) =>
      received.push(`${event.lastEventId}:${event.data as string}`);

    await vi.waitFor(() => expect(received).toEqual(['7:{"a":1}']));
    const headers = (fetchMock.mock.calls[0] as [string, RequestInit])[1]
      .headers as Record<string, string>;
    expect(headers['Last-Event-ID']).toBe('6');
    source.close();
  });

  it('gives a named event to its listeners only, and an unnamed one to onmessage', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            'id: 1\nevent: conversation\ndata: {"a":1}\n\n' +
              'event: nobody\ndata: {}\n\n' +
              'data: plain\n\n',
            { status: 200, headers: { 'Content-Type': 'text/event-stream' } }
          )
        )
    );
    const named: string[] = [];
    const unnamed: string[] = [];
    const source = new KeyedEventSource('/api/stream');
    source.addEventListener('conversation', (event) =>
      named.push(`${event.type}:${event.lastEventId}:${event.data as string}`)
    );
    source.addEventListener('conversation', () => named.push('second'));
    source.onmessage = (event) => unnamed.push(event.data as string);

    await vi.waitFor(() => expect(unnamed).toEqual(['plain']));
    expect(named).toEqual(['conversation:1:{"a":1}', 'second']);
    source.close();
  });
});
