// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import {
  applyConversation,
  applyEvent,
  EMPTY_AGENTS,
  orderedConversations,
  waitingCount,
} from '../agentItems';
import {
  createConversation,
  createEvent,
} from '@/test/factories/conversationFactory';

const fold = (...events: Parameters<typeof createEvent>[0][]) =>
  events.reduce(
    (state, event) => applyEvent(state, createEvent(event)),
    EMPTY_AGENTS
  );

describe('folding conversation events into items', () => {
  it('appends the pieces of one message and keeps who sent it', () => {
    const state = fold(
      {
        seq: 1,
        data: { role: 'user', author: 'ann', text: 'Fix ', more: true },
      },
      { seq: 2, data: { role: 'user', text: 'it', more: false, queued: true } }
    );

    expect(state.items).toEqual([
      expect.objectContaining({
        kind: 'message',
        role: 'user',
        author: 'ann',
        text: 'Fix it',
        queued: true,
      }),
    ]);
  });

  it('defaults a message to the agent and keeps the earlier role of an item', () => {
    expect(fold({ data: { text: 'x' } }).items[0]).toMatchObject({
      role: 'agent',
    });
    expect(
      fold(
        { seq: 1, data: { role: 'thought', text: 'a' } },
        { seq: 2, data: { text: 'b' } }
      ).items[0]
    ).toMatchObject({ role: 'thought', text: 'ab' });
  });

  it('lets a later tool event replace only the fields it carries', () => {
    const state = fold(
      {
        seq: 1,
        kind: 'TOOL',
        itemId: 't',
        data: { title: 'ls', status: 'running', input: { path: '.' } },
      },
      {
        seq: 2,
        kind: 'TOOL',
        itemId: 't',
        data: { status: 'completed', output: 'a.txt', truncated: true },
      }
    );

    expect(state.items).toHaveLength(1);
    expect(state.items[0]).toMatchObject({
      kind: 'tool',
      title: 'ls',
      status: 'completed',
      input: '{\n  "path": "."\n}',
      output: 'a.txt',
      truncated: true,
    });
  });

  it('gives a tool with nothing a name and no text', () => {
    expect(
      fold({ kind: 'TOOL', itemId: 't', data: {} }).items[0]
    ).toMatchObject({
      title: 'tool',
      input: '',
      output: '',
    });
  });

  it('updates a request in place as it is answered', () => {
    const state = fold(
      {
        seq: 1,
        kind: 'REQUEST',
        itemId: 'r1',
        data: { inputRequestId: 'r1', prompt: 'Run rm?', state: 'open' },
      },
      {
        seq: 2,
        kind: 'REQUEST',
        itemId: 'r1',
        data: { state: 'answered', decision: 'approve', by: 'key:ann' },
      }
    );

    expect(state.items).toEqual([
      expect.objectContaining({
        kind: 'request',
        requestId: 'r1',
        prompt: 'Run rm?',
        state: 'answered',
        decision: 'approve',
        by: 'key:ann',
      }),
    ]);
  });

  it('keeps the plan, results and notices as items of their own', () => {
    const state = fold(
      {
        seq: 1,
        kind: 'PLAN',
        itemId: 'plan',
        data: { entries: [{ text: 'a', status: 'done' }, null] },
      },
      { seq: 2, kind: 'PLAN', itemId: 'plan', data: { entries: 'bad' } },
      {
        seq: 3,
        kind: 'RESULT',
        itemId: null,
        data: { status: 'success', text: 'done' },
      },
      {
        seq: 4,
        kind: 'NOTICE',
        itemId: null,
        data: { level: 'WARN', text: 'careful' },
      },
      {
        seq: 5,
        kind: 'NOTICE',
        itemId: null,
        data: { level: 'INFO', text: 'again' },
      }
    );

    expect(state.items.map((item) => item.kind)).toEqual([
      'plan',
      'result',
      'notice',
      'notice',
    ]);
    expect(state.items[0]).toMatchObject({ entries: [] });
  });

  it('drops terminal output and state changes, and any event it already has', () => {
    const state = fold(
      { seq: 1, kind: 'TERMINAL', itemId: null, data: { data: 'AAAA' } },
      { seq: 2, kind: 'STATE', itemId: null, data: { state: 'IDLE' } },
      { seq: 3, data: { text: 'once' } },
      { seq: 3, data: { text: 'twice' } },
      { seq: 2, data: { text: 'older' } }
    );

    expect(state.items).toHaveLength(1);
    expect(state.items[0]).toMatchObject({ text: 'once' });
    expect(state.lastSeq).toBe(3);
  });

  it('keeps the items of two conversations apart even with the same item id', () => {
    const state = fold(
      { seq: 1, conversationId: 'a', itemId: 'prompt', data: { text: 'one' } },
      { seq: 2, conversationId: 'b', itemId: 'prompt', data: { text: 'two' } }
    );

    expect(state.items).toHaveLength(2);
  });
});

describe('events with fields missing or of a kind that is not text', () => {
  it('defaults what an event leaves out', () => {
    const state = fold(
      { seq: 1, kind: 'REQUEST', itemId: 'r', data: {} },
      { seq: 2, kind: 'RESULT', itemId: null, data: {} },
      { seq: 3, kind: 'NOTICE', itemId: null, data: { level: 5, text: null } },
      { seq: 4, kind: 'MESSAGE', itemId: 'x', data: { role: 7, text: 9 } }
    );

    expect(state.items).toEqual([
      expect.objectContaining({
        kind: 'request',
        requestId: '',
        prompt: '',
        state: '',
        decision: '',
        by: '',
      }),
      expect.objectContaining({
        kind: 'result',
        status: '',
        reason: '',
        text: '',
      }),
      expect.objectContaining({ kind: 'notice', level: '', text: '' }),
      expect.objectContaining({ kind: 'message', role: 'agent', text: '' }),
    ]);
  });

  it('keeps the title, status and text of an item that a later event does not repeat', () => {
    const state = fold(
      {
        seq: 1,
        kind: 'TOOL',
        itemId: 't',
        data: { title: 'ls', status: 'running', input: 'a', output: 'b' },
      },
      { seq: 2, kind: 'TOOL', itemId: 't', data: {} },
      { seq: 3, kind: 'TOOL', itemId: 'u', data: { title: 'x', input: null } },
      {
        seq: 4,
        kind: 'REQUEST',
        itemId: 'r',
        data: {
          inputRequestId: 'r',
          prompt: 'p',
          state: 's',
          decision: 'd',
          by: 'b',
        },
      },
      { seq: 5, kind: 'REQUEST', itemId: 'r', data: {} }
    );

    expect(state.items[0]).toMatchObject({
      title: 'ls',
      status: 'running',
      input: 'a',
      output: 'b',
    });
    expect(state.items[1]).toMatchObject({ title: 'x', input: '' });
    expect(state.items[2]).toMatchObject({
      requestId: 'r',
      prompt: 'p',
      state: 's',
      decision: 'd',
      by: 'b',
    });
  });

  it('orders conversations that began together by their sequence number', () => {
    let state = applyConversation(
      EMPTY_AGENTS,
      createConversation({ id: 'b', seq: 9 })
    );
    state = applyConversation(state, createConversation({ id: 'a', seq: 4 }));

    expect(orderedConversations(state).map((c) => c.id)).toEqual(['a', 'b']);
    expect(waitingCount(state)).toBe(0);
  });
});

describe('the conversations of a run', () => {
  it('keeps the newest record of a conversation and orders them by when they began', () => {
    let state = applyConversation(
      EMPTY_AGENTS,
      createConversation({
        id: 'late',
        startedAt: '2026-11-16T11:00:00Z',
        seq: 5,
      })
    );
    state = applyConversation(
      state,
      createConversation({ id: 'early', seq: 3, state: 'WAITING' })
    );
    state = applyConversation(
      state,
      createConversation({ id: 'early', seq: 2, state: 'RUNNING' })
    );

    expect(orderedConversations(state).map((c) => c.id)).toEqual([
      'early',
      'late',
    ]);
    expect(state.conversations.early.state).toBe('WAITING');
    expect(waitingCount(state)).toBe(1);
  });
});
