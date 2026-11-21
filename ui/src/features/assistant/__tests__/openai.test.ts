// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { openAiModel, toOpenAi } from '../lib/models/openai';
import { collect, sseResponse } from '../testFixtures';
import type { ToolSpec } from '../types';

const signal = new AbortController().signal;
const tool: ToolSpec = {
  name: 'list_sessions',
  description: 'Lists sessions',
  inputSchema: { type: 'object' },
};

afterEach(() => vi.unstubAllGlobals());

describe('toOpenAi', () => {
  it('puts the system prompt first and maps every kind of message', () => {
    expect(
      toOpenAi('sys', [
        { role: 'user', text: 'hi' },
        {
          role: 'assistant',
          text: 'a',
          calls: [{ id: 'c', name: 'n', args: { x: 1 } }],
        },
        { role: 'tool', id: 'c', name: 'n', result: 'r', isError: false },
        { role: 'assistant', text: '', calls: [] },
      ])
    ).toEqual([
      { role: 'system', content: 'sys' },
      { role: 'user', content: 'hi' },
      {
        role: 'assistant',
        content: 'a',
        tool_calls: [
          {
            id: 'c',
            type: 'function',
            function: { name: 'n', arguments: '{"x":1}' },
          },
        ],
      },
      { role: 'tool', tool_call_id: 'c', content: 'r' },
      { role: 'assistant', content: null, tool_calls: undefined },
    ]);
  });
});

describe('openAiModel', () => {
  const stream = (events: unknown[]) => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(events));
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  };
  const run = (tools: ToolSpec[] = [tool], baseUrl?: string) =>
    collect(
      openAiModel({ key: 'k', model: 'm', baseUrl }).stream(
        'sys',
        [{ role: 'user', text: 'q' }],
        tools,
        signal
      )
    );

  it('sends the tools and the base URL through the relay', async () => {
    const fetchMock = stream([]);

    await run([tool], 'http://h/v1');

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['X-Assistant-Base-Url']).toBe('http://h/v1');
    expect(JSON.parse(init.body).tools).toEqual([
      {
        type: 'function',
        function: {
          name: 'list_sessions',
          description: 'Lists sessions',
          parameters: { type: 'object' },
        },
      },
    ]);
  });

  it('leaves the tools out when there are none', async () => {
    const fetchMock = stream([]);

    await run([]);

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).not.toHaveProperty(
      'tools'
    );
  });

  it('streams text and assembles calls by index, then by id when the index is missing', async () => {
    stream([
      { choices: [] },
      { choices: [{ delta: { content: 'Hi' } }] },
      { choices: [{ delta: {} }] },
      {
        choices: [
          {
            delta: {
              tool_calls: [
                {
                  index: 0,
                  id: 'a',
                  function: { name: 'list_sessions', arguments: '{"x"' },
                },
              ],
            },
          },
        ],
      },
      {
        choices: [
          {
            delta: {
              tool_calls: [{ index: 0, function: { arguments: ':1}' } }],
            },
          },
        ],
      },
      {
        choices: [
          {
            delta: { tool_calls: [{ id: 'b', function: { name: 'whoami' } }] },
          },
        ],
      },
      {
        choices: [{ delta: { tool_calls: [{ id: 'b' }] } }],
      },
      '[DONE]',
      { choices: [{ delta: { content: 'ignored after done' } }] },
    ]);

    expect(await run()).toEqual([
      { type: 'text', delta: 'Hi' },
      {
        type: 'call',
        call: { id: 'a', name: 'list_sessions', args: { x: 1 } },
      },
      { type: 'call', call: { id: 'b', name: 'whoami', args: {} } },
    ]);
    expect(openAiModel({ key: 'k', model: 'gpt' }).label).toBe(
      'gpt · your key'
    );
  });

  it('throws the error of the stream', async () => {
    stream([{ error: { message: 'quota' } }]);

    await expect(run()).rejects.toThrow('quota');
  });
});
