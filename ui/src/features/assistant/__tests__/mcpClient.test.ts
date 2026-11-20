// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setApiKey } from '@/lib/apiKey';
import { callTool, listTools, resetTools } from '../lib/mcpClient';

const json = (body: unknown, init?: ResponseInit) =>
  new Response(JSON.stringify(body), {
    headers: { 'content-type': 'application/json' },
    ...init,
  });

beforeEach(() => resetTools());
afterEach(() => {
  setApiKey(null);
  vi.unstubAllGlobals();
});

describe('listTools', () => {
  it('asks once with the Yukta key and keeps the answer', async () => {
    setApiKey('yk');
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json({ result: { tools: [{ name: 'a' }] } }));
    vi.stubGlobal('fetch', fetchMock);

    expect(await listTools()).toEqual([{ name: 'a' }]);
    expect(await listTools()).toEqual([{ name: 'a' }]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/mcp');
    expect(init.headers.Authorization).toBe('Bearer yk');
    expect(JSON.parse(init.body)).toMatchObject({
      jsonrpc: '2.0',
      method: 'tools/list',
    });
  });

  it('asks again after a failure', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 500 }))
      .mockResolvedValueOnce(json({ result: { tools: [] } }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(listTools()).rejects.toThrow('answered 500');
    expect(await listTools()).toEqual([]);
  });

  it('reads an event-stream answer', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('data: {"result":{"tools":[{"name":"s"}]}}\n\n', {
          headers: { 'content-type': 'text/event-stream' },
        })
      )
    );

    expect(await listTools()).toEqual([{ name: 's' }]);
  });
});

describe('callTool', () => {
  it('joins the text parts of the result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      json({
        result: {
          content: [
            { type: 'text', text: 'a' },
            { type: 'image' },
            { type: 'text', text: 'b' },
          ],
        },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    expect(await callTool('t', { x: 1 })).toEqual({
      text: 'a\n\nb',
      isError: false,
    });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).params).toEqual({
      name: 't',
      arguments: { x: 1 },
    });
  });

  it('reports a failure the tool names, and a result with no content', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          json({
            result: { content: [{ type: 'text', text: 'no' }], isError: true },
          })
        )
        .mockResolvedValueOnce(json({ result: {} }))
    );

    expect(await callTool('t', {})).toEqual({ text: 'no', isError: true });
    expect(await callTool('t', {})).toEqual({ text: '', isError: false });
  });

  it('turns a JSON-RPC error and a 403 into messages', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json({ error: { message: 'bad args' } }))
        .mockResolvedValueOnce(new Response('', { status: 403 }))
    );

    await expect(callTool('t', {})).rejects.toThrow('bad args');
    await expect(callTool('t', {})).rejects.toThrow('role does not allow');
  });
});
