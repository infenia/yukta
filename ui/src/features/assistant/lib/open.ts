// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Fired to open the assistant from anywhere (the command palette). */
export const OPEN_ASSISTANT_EVENT = 'yukta:open-assistant';

export function openAssistant(): void {
  window.dispatchEvent(new Event(OPEN_ASSISTANT_EVENT));
}

/** Fired by the conversation as it starts and stops working; `detail` is whether it is working. */
export const BUSY_ASSISTANT_EVENT = 'yukta:assistant-busy';
