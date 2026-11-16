// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Conversation, ConversationEvent } from '@/types/conversations';

export function createConversation(
  overrides: Partial<Conversation> = {}
): Conversation {
  return {
    id: 'c1',
    executionId: 'exec-1',
    rootExecutionId: 'exec-1',
    sessionId: 's1',
    workflowId: 'w1',
    nodeId: 'review',
    messageId: 'm1',
    harness: 'claude-code',
    protocol: 'JSONL',
    model: null,
    capabilities: ['message'],
    state: 'RUNNING',
    outcome: null,
    reason: null,
    turn: 0,
    queued: 0,
    steerAudience: ['role:OPERATOR'],
    startedAt: '2026-11-16T10:00:00Z',
    updatedAt: '2026-11-16T10:00:00Z',
    endedAt: null,
    seq: 1,
    ...overrides,
  };
}

export function createEvent(
  overrides: Partial<ConversationEvent> = {}
): ConversationEvent {
  return {
    seq: 1,
    conversationId: 'c1',
    timestamp: '2026-11-16T10:00:01Z',
    kind: 'MESSAGE',
    itemId: 'm',
    data: { role: 'agent', text: 'hello', more: false },
    ...overrides,
  };
}
