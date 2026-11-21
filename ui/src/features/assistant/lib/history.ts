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
