// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { setApiKey } from '@/lib/apiKey';
import { streamProxy } from '../lib/proxy';
import { collect, sseResponse } from '../testFixtures';

const signal = new AbortController().signal;

afterEach(() => {
  setApiKey(null);
  vi.unstubAllGlobals();
});

describe('streamProxy', () => {
  it('posts the body through the relay with the key and Yukta credentials', async () => {
    setApiKey('yk');
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(['a', 'b']));
    vi.stubGlobal('fetch', fetchMock);

    const out = await collect(
      streamProxy('anthropic', { q: 1 }, { key: 'sk' }, signal)
    );

    expect(out).toEqual(['a', 'b']);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/assistant/proxy/anthropic');
    expect(init.headers).toEqual({
      'Content-Type': 'application/json',
      'X-Assistant-Key': 'sk',
      Authorization: 'Bearer yk',
    });
    expect(init.body).toBe('{"q":1}');
  });

  it('sends the base URL when there is one', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await collect(
      streamProxy('openai', {}, { key: 'k', baseUrl: 'http://h/v1' }, signal)
    );

    expect(fetchMock.mock.calls[0][1].headers['X-Assistant-Base-Url']).toBe(
      'http://h/v1'
    );
  });

  it.each([
    ['the provider error', '{"error":{"message":"bad key"}}', 401, 'bad key'],
    ['the server message', '{"message":"not allowed"}', 403, 'not allowed'],
    ['a JSON body with neither', '{"a":1}', 500, '{"a":1}'],
    ['plain text', 'upstream down', 502, 'upstream down'],
    ['the status text of an empty body', '', 503, 'Service Unavailable'],
  ])('throws %s', async (_, body, status, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(body, {
          status,
          statusText: status === 503 ? 'Service Unavailable' : '',
        })
      )
    );

    await expect(
      collect(streamProxy('openai', {}, { key: 'k' }, signal))
    ).rejects.toThrow(message);
  });
});
