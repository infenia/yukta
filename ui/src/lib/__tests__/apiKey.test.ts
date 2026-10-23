// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchApi } from '../apiClient';
import {
  API_KEY_REQUIRED_EVENT,
  authHeaders,
  getApiKey,
  setApiKey,
} from '../apiKey';
import { KeyedEventSource, openEventStream } from '../keyedEventSource';

afterEach(() => {
  setApiKey(null);
  vi.unstubAllGlobals();
});

function stream(...chunks: string[]): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(new TextEncoder().encode(c)));
      controller.close();
    },
  });
  return new Response(body, { status: 200 });
}

describe('apiKey', () => {
  it('stores and clears the key', () => {
    expect(authHeaders()).toEqual({});
    setApiKey('k1');
    expect(getApiKey()).toBe('k1');
    expect(authHeaders()).toEqual({ Authorization: 'Bearer k1' });
    setApiKey(null);
    expect(getApiKey()).toBeNull();
  });

  it('survives blocked storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(getApiKey()).toBeNull();
    expect(() => setApiKey('k')).not.toThrow();
  });

  it('sends the key with fetchApi and asks for one on 401', async () => {
    setApiKey('k2');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response('{}', { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);
    const required = vi.fn();
    window.addEventListener(API_KEY_REQUIRED_EVENT, required);
    await expect(fetchApi('/api/x')).rejects.toMatchObject({ status: 401 });
    window.removeEventListener(API_KEY_REQUIRED_EVENT, required);
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer k2');
    expect(required).toHaveBeenCalledOnce();
  });
});

describe('KeyedEventSource', () => {
  it('opens a keyed stream only when a key is set', () => {
    vi.stubGlobal('EventSource', vi.fn());
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})));
    openEventStream('/s');
    expect(EventSource).toHaveBeenCalledWith('/s');
    setApiKey('k');
    expect(openEventStream('/s')).toBeInstanceOf(KeyedEventSource);
  });

  it('parses data frames split across chunks and sends the key', async () => {
    setApiKey('k3');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(stream('data: {"a":1}\n\nda', 'ta:{"a":2}\r\n\r\n: ping\n\n'));
    vi.stubGlobal('fetch', fetchMock);
    const messages: string[] = [];
    const opened = vi.fn();
    const failed = new Promise<void>((resolve) => {
      const source = new KeyedEventSource('/api/s');
      source.onopen = opened;
      source.onmessage = (e) => messages.push(e.data as string);
      source.onerror = () => resolve();
    });
    await failed;
    expect(messages).toEqual(['{"a":1}', '{"a":2}']);
    expect(opened).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer k3');
  });

  it('reports a rejection and asks for a key on 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    const required = vi.fn();
    window.addEventListener(API_KEY_REQUIRED_EVENT, required);
    await new Promise<void>((resolve) => {
      const source = new KeyedEventSource('/api/s');
      source.onerror = () => resolve();
    });
    window.removeEventListener(API_KEY_REQUIRED_EVENT, required);
    expect(required).toHaveBeenCalledOnce();
  });

  it('stays silent after close', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('aborted')));
    const source = new KeyedEventSource('/api/s');
    const onerror = vi.fn();
    source.onerror = onerror;
    source.close();
    await new Promise((r) => setTimeout(r, 0));
    expect(onerror).not.toHaveBeenCalled();
  });
});
