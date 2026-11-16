// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useMemo, useState } from 'react';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { Conversation, ConversationEvent } from '@/types/conversations';
import {
  applyConversation,
  applyEvent,
  EMPTY_AGENTS,
  orderedConversations,
  waitingCount,
  type AgentState,
} from '../lib/agentItems';

/** The events of the stream, which names them. A stable array: the hook depends on it. */
const EVENT_NAMES = ['conversation', 'event'] as const;

/**
 * Follow every conversation with an AI agent of a run and of the runs it starts: the conversations
 * and what is said in them, folded into items. The server resumes a reopened stream after the last
 * event id, and an event already seen is dropped, so a reconnect shows no line twice.
 */
export function useAgentStream(executionId: string | null, live = true) {
  const [held, setHeld] = useState<{
    executionId: string | null;
    state: AgentState;
  }>({
    executionId,
    state: EMPTY_AGENTS,
  });
  // Render-phase reset: the state belongs to one run and empties when the run changes
  if (held.executionId !== executionId) {
    setHeld({ executionId, state: EMPTY_AGENTS });
  }

  const onMessage = useCallback(
    (data: Conversation | ConversationEvent, name?: string) => {
      setHeld((current) => ({
        ...current,
        state:
          name === 'conversation'
            ? applyConversation(current.state, data as Conversation)
            : applyEvent(current.state, data as ConversationEvent),
      }));
    },
    []
  );

  const url = executionId
    ? api`/api/executions/${executionId}/conversations/stream?tree=true&terminal=false`
    : null;
  const { status } = useEventSourceJson<Conversation | ConversationEvent>(
    url,
    onMessage,
    live,
    undefined,
    EVENT_NAMES
  );

  const state = held.executionId === executionId ? held.state : EMPTY_AGENTS;
  return useMemo(
    () => ({
      status,
      conversations: orderedConversations(state),
      items: state.items,
      waiting: waitingCount(state),
    }),
    [status, state]
  );
}
