// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { anthropicModel, toAnthropic } from '../lib/models/anthropic';
import { collect, sseResponse } from '../testFixtures';
import type { Msg, ToolSpec } from '../types';

const signal = new AbortController().signal;
const tool: ToolSpec = {
  name: 'list_sessions',
  description: 'Lists sessions',
  inputSchema: { type: 'object' },
};

afterEach(() => vi.unstubAllGlobals());

describe('toAnthropic', () => {
  it('maps text turns, tool calls and merges consecutive tool results', () => {
    const history: Msg[] = [
      { role: 'user', text: 'hi' },
      {
        role: 'assistant',
        text: 'looking',
        calls: [
          { id: 'a', name: 'x', args: { n: 1 } },
          { id: 'b', name: 'y', args: {} },
        ],
      },
      { role: 'tool', id: 'a', name: 'x', result: 'one', isError: false },
      { role: 'tool', id: 'b', name: 'y', result: 'two', isError: true },
      {
        role: 'assistant',
        text: '',
        calls: [{ id: 'c', name: 'z', args: {} }],
      },
      { role: 'tool', id: 'c', name: 'z', result: 'three', isError: false },
    ];

    expect(toAnthropic(history)).toEqual([
      { role: 'user', content: 'hi' },
      {
        role: 'assistant',
        content: [
          { type: 'text', text: 'looking' },
          { type: 'tool_use', id: 'a', name: 'x', input: { n: 1 } },
          { type: 'tool_use', id: 'b', name: 'y', input: {} },
        ],
      },
      {
        role: 'user',
        content: [
          {
            type: 'tool_result',
            tool_use_id: 'a',
            content: 'one',
            is_error: false,
          },
          {
            type: 'tool_result',
            tool_use_id: 'b',
            content: 'two',
            is_error: true,
          },
        ],
      },
      {
        role: 'assistant',
        content: [{ type: 'tool_use', id: 'c', name: 'z', input: {} }],
      },
      {
        role: 'user',
        content: [
          {
            type: 'tool_result',
            tool_use_id: 'c',
            content: 'three',
            is_error: false,
          },
        ],
      },
    ]);
  });

  it('starts a user message for a tool result that follows plain user text', () => {
    const out = toAnthropic([
      { role: 'user', text: 'hi' },
      { role: 'tool', id: 'a', name: 'x', result: 'r', isError: false },
    ]);

    expect(out).toHaveLength(2);
    expect(out[1].content).toHaveLength(1);
  });
});

describe('anthropicModel', () => {
  const stream = (events: unknown[]) => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(events));
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  };
  const run = () =>
    collect(
      anthropicModel({ key: 'k', model: 'm' }).stream(
        'sys',
        [{ role: 'user', text: 'q' }],
        [tool],
        signal
      )
    );

  it('labels the model and sends the request through the relay', async () => {
    const fetchMock = stream([]);

    await run();

    expect(anthropicModel({ key: 'k', model: 'm' }).label).toBe('m · your key');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      model: 'm',
      max_tokens: 4096,
      stream: true,
      system: 'sys',
      messages: [{ role: 'user', content: 'q' }],
      tools: [
        {
          name: 'list_sessions',
          description: 'Lists sessions',
          input_schema: { type: 'object' },
        },
      ],
    });
  });

  it('streams text and assembles a tool call from its json deltas', async () => {
    stream([
      { type: 'message_start' },
      {
        type: 'content_block_start',
        index: 0,
        content_block: { type: 'text' },
      },
      {
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'text_delta', text: 'Hel' },
      },
      {
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'text_delta', text: 'lo' },
      },
      {
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'thinking_delta', thinking: '…' },
      },
      { type: 'content_block_stop', index: 0 },
      {
        type: 'content_block_start',
        index: 1,
        content_block: { type: 'tool_use', id: 't1', name: 'list_sessions' },
      },
      {
        type: 'content_block_delta',
        index: 1,
        delta: { type: 'input_json_delta', partial_json: '{"a":' },
      },
      {
        type: 'content_block_delta',
        index: 1,
        delta: { type: 'input_json_delta', partial_json: '1}' },
      },
      { type: 'content_block_stop', index: 1 },
      {
        type: 'content_block_start',
        index: 2,
        content_block: { type: 'tool_use', id: 't2', name: 'whoami' },
      },
      { type: 'content_block_stop', index: 2 },
    ]);

    expect(await run()).toEqual([
      { type: 'text', delta: 'Hel' },
      { type: 'text', delta: 'lo' },
      {
        type: 'call',
        call: { id: 't1', name: 'list_sessions', args: { a: 1 } },
      },
      { type: 'call', call: { id: 't2', name: 'whoami', args: {} } },
    ]);
  });

  it('throws the error event of the stream', async () => {
    stream([{ type: 'error', error: { message: 'overloaded' } }]);

    await expect(run()).rejects.toThrow('overloaded');
  });
});
