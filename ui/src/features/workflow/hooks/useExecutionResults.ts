// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { ResultEvent } from '../types/workflow';

/** Most result events kept for display; older ones are dropped from the front. */
export const MAX_RESULT_EVENTS = 500;

interface ResultState {
  events: ResultEvent[];
  complete: boolean;
  status: string | null;
}

const EMPTY: ResultState = { events: [], complete: false, status: null };

/**
 * Follow what reaches the terminal nodes of an execution (replayed history, then live) until the
 * execution ends. The server replays on every connection, so events already held are skipped by
 * sequence number. Resets when the execution changes.
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
      const { events } = previous.value;
      if (events.some((held) => held.sequence === event.sequence)) {
        return previous;
      }
      const next = [...events, event].slice(-MAX_RESULT_EVENTS);
      return {
        ...previous,
        value: {
          events: next,
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
    results: current.value.events.filter((event) => event.kind === 'RESULT'),
    complete: current.value.complete,
    executionStatus: current.value.status,
    isStreaming:
      !current.value.complete && (status === 'open' || status === 'connecting'),
  };
}
