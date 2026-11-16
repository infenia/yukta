// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { byNode } from '../agentsByNode';
import { createConversation } from '@/test/factories/conversationFactory';

const ended = { state: 'ENDED', outcome: 'success', endedAt: 't' } as const;

describe('byNode', () => {
  it('keeps one conversation for each node', () => {
    const picked = byNode([
      createConversation({ id: 'a', nodeId: 'review' }),
      createConversation({ id: 'b', nodeId: 'fix' }),
    ]);
    expect(Object.keys(picked).sort()).toEqual(['fix', 'review']);
  });

  it('prefers a live conversation to an ended one, in either order', () => {
    const live = createConversation({ id: 'live', nodeId: 'n' });
    const over = createConversation({ id: 'over', nodeId: 'n', ...ended });

    expect(byNode([over, live]).n.id).toBe('live');
    expect(byNode([live, over]).n.id).toBe('live');
  });

  it('takes the later one of two that are both live or both ended', () => {
    expect(
      byNode([
        createConversation({ id: 'first', nodeId: 'n' }),
        createConversation({ id: 'second', nodeId: 'n' }),
      ]).n.id
    ).toBe('second');
    expect(
      byNode([
        createConversation({ id: 'first', nodeId: 'n', ...ended }),
        createConversation({ id: 'second', nodeId: 'n', ...ended }),
      ]).n.id
    ).toBe('second');
  });
});
