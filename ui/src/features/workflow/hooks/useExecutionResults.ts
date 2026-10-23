// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { ResultEvent } from '../types/workflow';

/** Most result events kept for display; older ones are dropped from the front. */
export const MAX_RESULT_EVENTS = 500;

interface ResultState {
  /** Only RESULT events: NODE_END / EXECUTION_END must not push real results out of the cap. */
  events: ResultEvent[];
  /** Highest sequence seen, kept after old results are evicted so a replay cannot re-add them. */
  lastSequence: number;
  complete: boolean;
  status: string | null;
}

const EMPTY: ResultState = {
  events: [],
  lastSequence: -1,
  complete: false,
  status: null,
};

/**
 * Follow what reaches the terminal nodes of an execution (replayed history, then live) until the
 * execution ends. The server replays on every connection, so every event at or below the highest
 * sequence seen is skipped, also after it was evicted from the capped list. Resets when the
 * execution changes.
 */
export function useExecutionResults(
  sessionId: string,
  executionId: string | null
) {
  const [state, setState] = useState<{
    executionId: string | null;
    value: ResultState;
  }>({
    executionId,
    value: EMPTY,
  });

  // Render-phase reset: the events belong to one execution
  let current = state;
  if (state.executionId !== executionId) {
    current = { executionId, value: EMPTY };
    setState(current);
  }

  const url =
    sessionId && executionId
      ? api`/api/sessions/${sessionId}/executions/${executionId}/results`
      : null;

  const onEvent = useCallback((event: ResultEvent) => {
    setState((previous) => {
      const { events, lastSequence } = previous.value;
      if (event.sequence <= lastSequence) {
        return previous;
      }
      const next =
        event.kind === 'RESULT'
          ? [...events, event].slice(-MAX_RESULT_EVENTS)
          : events;
      return {
        ...previous,
        value: {
          events: next,
          lastSequence: event.sequence,
          complete: previous.value.complete || event.kind === 'EXECUTION_END',
          status:
            event.kind === 'EXECUTION_END'
              ? event.status
              : previous.value.status,
        },
      };
    });
  }, []);

  // Once the execution has ended the server closes the stream; do not reopen it.
  const { status } = useEventSourceJson<ResultEvent>(
    url,
    onEvent,
    !current.value.complete
  );

  return {
    results: current.value.events,
    complete: current.value.complete,
    executionStatus: current.value.status,
    isStreaming:
      !current.value.complete && (status === 'open' || status === 'connecting'),
  };
}
