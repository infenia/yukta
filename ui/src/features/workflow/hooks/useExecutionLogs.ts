// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { LogEntry } from '../types/workflow';

/** Ring-buffer cap keeping the log panel responsive on long executions. */
export const MAX_LOG_ENTRIES = 2000;

/**
 * Stream structured execution log entries (history first, then live).
 *
 * Entries accumulate in a capped ring buffer that resets when the execution changes.
 */
export function useExecutionLogs(sessionId: string, executionId: string | null) {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [bufferExecutionId, setBufferExecutionId] = useState(executionId);

  // Render-phase reset: the buffer belongs to one execution and empties when it changes
  if (bufferExecutionId !== executionId) {
    setBufferExecutionId(executionId);
    setEntries([]);
  }

  const url =
    sessionId && executionId
      ? `/api/sessions/${sessionId}/executions/${executionId}/logs/entries`
      : null;

  const onEntry = useCallback((entry: LogEntry) => {
    setEntries((previous) => {
      const next = [...previous, entry];
      return next.length > MAX_LOG_ENTRIES ? next.slice(next.length - MAX_LOG_ENTRIES) : next;
    });
  }, []);

  const { status } = useEventSourceJson<LogEntry>(url, onEntry);

  const clear = useCallback(() => setEntries([]), []);

  return {
    entries,
    isStreaming: status === 'open' || status === 'connecting',
    clear,
  };
}
