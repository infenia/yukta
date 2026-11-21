// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearChat, loadChat, saveChat } from '../lib/history';
import type { Msg } from '../types';

const done: Msg[] = [
  { role: 'user', text: 'hi' },
  { role: 'assistant', text: 'Hello', calls: [] },
];

beforeEach(() => sessionStorage.clear());

describe('history', () => {
  it('starts empty, keeps what was saved and forgets it on clear', () => {
    expect(loadChat()).toEqual([]);

    saveChat(done);
    expect(loadChat()).toEqual(done);

    clearChat();
    expect(loadChat()).toEqual([]);
  });

  it('cuts back to the last finished answer when a call has no result', () => {
    saveChat([
      ...done,
      { role: 'user', text: 'again' },
      {
        role: 'assistant',
        text: '',
        calls: [{ id: 'c1', name: 'list_sessions', args: {} }],
      },
    ]);

    expect(loadChat()).toEqual(done);
  });

  it('carries on without storage, or with something that is not a conversation', () => {
    sessionStorage.setItem('yukta.assistant.chat', '{broken');
    expect(loadChat()).toEqual([]);

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    expect(() => saveChat(done)).not.toThrow();
  });
});
