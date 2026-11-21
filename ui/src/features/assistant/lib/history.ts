// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Msg } from '../types';

const CHAT_KEY = 'yukta.assistant.chat';

/** Storage can be missing or throw (private window, full quota): carry on without it. */
function safe<T>(fn: () => T, fallback: T): T {
  try {
    return fn();
  } catch {
    return fallback;
  }
}

/**
 * The conversation kept for this tab, cut back to its last finished answer: a reload while a
 * tool was waiting or running would otherwise leave a call with no result.
 */
export function loadChat(): Msg[] {
  const saved = safe<Msg[]>(
    () => JSON.parse(sessionStorage.getItem(CHAT_KEY) ?? '[]'),
    []
  );
  let end = saved.length;
  while (end > 0) {
    const m = saved[end - 1];
    if (m.role === 'assistant' && m.calls.length === 0) break;
    end--;
  }
  return saved.slice(0, end);
}

export const saveChat = (messages: Msg[]): void =>
  safe(
    () => sessionStorage.setItem(CHAT_KEY, JSON.stringify(messages)),
    undefined
  );

export const clearChat = (): void =>
  safe(() => sessionStorage.removeItem(CHAT_KEY), undefined);

const ARCHIVE_KEY = 'yukta.assistant.history';
/** Earlier conversations kept; the oldest go first. */
const KEEP = 20;

/** An earlier conversation, kept in this browser. */
export interface Archived {
  id: string;
  /** The first question. */
  title: string;
  at: number;
  messages: Msg[];
}

/** The earlier conversations, newest first; anything that is not a list of them reads as none. */
export function listArchive(): Archived[] {
  const saved = safe<unknown>(
    () => JSON.parse(localStorage.getItem(ARCHIVE_KEY) ?? '[]'),
    []
  );
  return Array.isArray(saved) ? (saved as Archived[]) : [];
}

const writeArchive = (list: Archived[]): void =>
  safe(
    () =>
      localStorage.setItem(ARCHIVE_KEY, JSON.stringify(list.slice(0, KEEP))),
    undefined
  );

/** Keeps the current conversation in the list (nothing to keep when it is empty). */
export function archiveChat(): void {
  const messages = loadChat();
  const first = messages.find((m) => m.role === 'user');
  if (!first) return;
  writeArchive([
    {
      id: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`,
      title: first.text.slice(0, 80),
      at: Date.now(),
      messages,
    },
    ...listArchive(),
  ]);
}

export const deleteArchived = (id: string): void =>
  writeArchive(listArchive().filter((chat) => chat.id !== id));

/** Makes an earlier conversation the current one; the one it replaces is kept. */
export function restoreChat(id: string): void {
  const chat = listArchive().find((entry) => entry.id === id);
  if (!chat) return;
  archiveChat();
  deleteArchived(id);
  saveChat(chat.messages);
}
