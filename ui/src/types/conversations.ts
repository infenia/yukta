// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** A conversation with an AI agent, as /api/conversations returns it. Text in it is untrusted. */
export interface Conversation {
  id: string;
  executionId: string;
  rootExecutionId: string;
  sessionId: string;
  workflowId: string;
  nodeId: string;
  messageId: string;
  harness: string;
  protocol: 'JSONL' | 'ACP' | 'PTY';
  model: string | null;
  capabilities: string[];
  state: ConversationState;
  outcome: 'success' | 'failure' | 'incomplete' | null;
  reason: string | null;
  turn: number;
  queued: number;
  steerAudience: string[];
  startedAt: string;
  updatedAt: string;
  endedAt: string | null;
  seq: number;
}

export type ConversationState =
  'STARTING' | 'RUNNING' | 'WAITING' | 'IDLE' | 'ENDED';

export type ConversationEventKind =
  | 'STATE'
  | 'MESSAGE'
  | 'TOOL'
  | 'PLAN'
  | 'REQUEST'
  | 'TERMINAL'
  | 'RESULT'
  | 'NOTICE';

/** One thing that happened in a conversation; the keys of `data` depend on the kind. */
export interface ConversationEvent {
  seq: number;
  conversationId: string;
  timestamp: string;
  kind: ConversationEventKind;
  itemId: string | null;
  data: Record<string, unknown>;
}
