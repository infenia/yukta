// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  archiveChat,
  clearChat,
  deleteArchived,
  listArchive,
  loadChat,
  restoreChat,
  saveChat,
} from '../lib/history';
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

describe('archive', () => {
  beforeEach(() => localStorage.clear());

  it('keeps a conversation on archive, brings it back on restore and keeps the one it replaces', () => {
    saveChat(done);
    archiveChat();
    const [first] = listArchive();
    expect(first.title).toBe('hi');

    const other: Msg[] = [
      { role: 'user', text: 'other' },
      { role: 'assistant', text: 'ok', calls: [] },
    ];
    saveChat(other);
    restoreChat(first.id);

    expect(loadChat()).toEqual(done);
    expect(listArchive().map((c) => c.title)).toEqual(['other']);
  });

  it('skips an empty chat, deletes, caps the list and shrugs off bad storage', () => {
    archiveChat();
    expect(listArchive()).toEqual([]);

    saveChat(done);
    for (let i = 0; i < 25; i++) archiveChat();
    expect(listArchive()).toHaveLength(20);
    deleteArchived(listArchive()[0].id);
    expect(listArchive()).toHaveLength(19);
    restoreChat('missing');

    localStorage.setItem('yukta.assistant.history', '{"a":1}');
    expect(listArchive()).toEqual([]);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full');
    });
    expect(() => archiveChat()).not.toThrow();
    vi.restoreAllMocks();
  });
});
