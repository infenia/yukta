// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext } from 'react';
import type { Conversation } from '@/types/conversations';

/** The conversation of each node that runs an agent: the live one, else the newest. */
export const AgentsByNode = createContext<Record<string, Conversation>>({});

/** Picks one conversation for each node: a live one before an ended one, then the later one. */
export function byNode(
  conversations: Conversation[]
): Record<string, Conversation> {
  const picked: Record<string, Conversation> = {};
  for (const c of conversations) {
    const held = picked[c.nodeId];
    const better =
      !held ||
      (held.state === 'ENDED' && c.state !== 'ENDED') ||
      (held.state === 'ENDED') === (c.state === 'ENDED');
    if (better) picked[c.nodeId] = c;
  }
  return picked;
}
