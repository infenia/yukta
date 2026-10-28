// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import { MAX_LOG_ENTRIES } from './useExecutionLogs';

const asText = (data: string) => data;

/**
 * The server's preformatted log lines (`.../logs`), read while `run` is set. Every new `run`
 * starts from an empty list, because the server replays history on each connection. The stream is
 * not reopened after an error for the same reason.
 */
export function useRawLogLines(
  sessionId: string,
  executionId: string | null,
  run: number | null
) {
  const [state, setState] = useState<{ key: string; lines: string[] }>({
    key: '',
    lines: [],
  });
  const key = `${sessionId}/${executionId}/${run}`;
  const url =
    run !== null && sessionId && executionId
      ? api`/api/sessions/${sessionId}/executions/${executionId}/logs`
      : null;

  const onLine = useCallback(
    (line: string) =>
      setState((previous) => ({
        key,
        lines: [...(previous.key === key ? previous.lines : []), line].slice(
          -MAX_LOG_ENTRIES
        ),
      })),
    [key]
  );
  useEventSourceJson<string>(url, onLine, false, asText);

  return state.key === key ? state.lines : [];
}
