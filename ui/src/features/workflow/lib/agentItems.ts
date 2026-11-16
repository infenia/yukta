// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Conversation, ConversationEvent } from '@/types/conversations';

/** Something an agent said or did, folded from the events that share an item id. */
export type AgentItem =
  | {
      key: string;
      conversationId: string;
      kind: 'message';
      role: string;
      author: string;
      text: string;
      queued: boolean;
    }
  | {
      key: string;
      conversationId: string;
      kind: 'tool';
      title: string;
      status: string;
      input: string;
      output: string;
      truncated: boolean;
    }
  | {
      key: string;
      conversationId: string;
      kind: 'plan';
      entries: { text: string; status: string }[];
    }
  | {
      key: string;
      conversationId: string;
      kind: 'request';
      requestId: string;
      prompt: string;
      state: string;
      decision: string;
      by: string;
    }
  | {
      key: string;
      conversationId: string;
      kind: 'result';
      status: string;
      reason: string;
      text: string;
    }
  | {
      key: string;
      conversationId: string;
      kind: 'notice';
      level: string;
      text: string;
    };

export interface AgentState {
  /** By conversation id. */
  conversations: Record<string, Conversation>;
  /** In the order they first appeared. */
  items: AgentItem[];
  /** The newest event applied; an event at or below it is a replay and is dropped. */
  lastSeq: number;
}

export const EMPTY_AGENTS: AgentState = {
  conversations: {},
  items: [],
  lastSeq: 0,
};

const text = (value: unknown): string =>
  typeof value === 'string' ? value : '';

/** An input or output a tool carries: text, or JSON for anything else. */
function shown(value: unknown): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

/** The newest state of a conversation replaces the one held. */
export function applyConversation(
  state: AgentState,
  conversation: Conversation
): AgentState {
  const held = state.conversations[conversation.id];
  if (held && held.seq > conversation.seq) return state;
  return {
    ...state,
    conversations: { ...state.conversations, [conversation.id]: conversation },
  };
}

function planEntries(value: unknown): { text: string; status: string }[] {
  if (!Array.isArray(value)) return [];
  return value.map((entry: Record<string, unknown>) => ({
    text: text(entry?.text),
    status: text(entry?.status),
  }));
}

/**
 * Folds one event into the items. Events of one item id describe one item: message pieces are
 * appended in order; a later tool, plan or request event replaces the fields it carries. Events
 * with no item id (results, notices) are items of their own. Terminal output and state changes
 * are not items.
 */
export function applyEvent(
  state: AgentState,
  event: ConversationEvent
): AgentState {
  if (event.seq <= state.lastSeq) return state;
  const next = { ...state, lastSeq: event.seq };
  const key = `${event.conversationId}/${event.itemId ?? `#${event.seq}`}`;
  const index = state.items.findIndex((item) => item.key === key);
  const held = index >= 0 ? state.items[index] : undefined;
  const item = foldItem(held, key, event);
  if (!item) return next;
  next.items =
    index >= 0
      ? state.items.map((existing, i) => (i === index ? item : existing))
      : [...state.items, item];
  return next;
}

function foldItem(
  held: AgentItem | undefined,
  key: string,
  event: ConversationEvent
): AgentItem | null {
  const d = event.data;
  const conversationId = event.conversationId;
  switch (event.kind) {
    case 'MESSAGE':
      return {
        key,
        conversationId,
        kind: 'message',
        role: text(d.role) || (held?.kind === 'message' ? held.role : 'agent'),
        author: text(d.author) || (held?.kind === 'message' ? held.author : ''),
        text: (held?.kind === 'message' ? held.text : '') + text(d.text),
        queued: d.queued === true || (held?.kind === 'message' && held.queued),
      };
    case 'TOOL': {
      const before = held?.kind === 'tool' ? held : undefined;
      return {
        key,
        conversationId,
        kind: 'tool',
        title: text(d.title) || before?.title || 'tool',
        status: text(d.status) || before?.status || '',
        input: 'input' in d ? shown(d.input) : (before?.input ?? ''),
        output: 'output' in d ? shown(d.output) : (before?.output ?? ''),
        truncated: d.truncated === true || before?.truncated === true,
      };
    }
    case 'PLAN':
      return {
        key,
        conversationId,
        kind: 'plan',
        entries: planEntries(d.entries),
      };
    case 'REQUEST': {
      const before = held?.kind === 'request' ? held : undefined;
      return {
        key,
        conversationId,
        kind: 'request',
        requestId: text(d.inputRequestId) || before?.requestId || '',
        prompt: text(d.prompt) || before?.prompt || '',
        state: text(d.state) || before?.state || '',
        decision: text(d.decision) || before?.decision || '',
        by: text(d.by) || before?.by || '',
      };
    }
    case 'RESULT':
      return {
        key,
        conversationId,
        kind: 'result',
        status: text(d.status),
        reason: text(d.reason),
        text: text(d.text),
      };
    case 'NOTICE':
      return {
        key,
        conversationId,
        kind: 'notice',
        level: text(d.level),
        text: text(d.text),
      };
    default:
      return null;
  }
}

/** The conversations in the order they began, newest last. */
export function orderedConversations(state: AgentState): Conversation[] {
  return Object.values(state.conversations).sort(
    (a, b) => a.startedAt.localeCompare(b.startedAt) || a.seq - b.seq
  );
}

/** How many conversations wait for someone. */
export function waitingCount(state: AgentState): number {
  return Object.values(state.conversations).filter((c) => c.state === 'WAITING')
    .length;
}
