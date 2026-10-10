// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useRef, useState } from 'react';
import { api } from '@/lib/apiClient';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import type { LogEntry } from '../types/workflow';

/** Ring-buffer cap keeping the log panel responsive on long executions. */
export const MAX_LOG_ENTRIES = 2000;

/**
 * Stream structured execution log entries (history first, then live).
 *
 * Entries accumulate in a capped ring buffer that resets when the execution changes. While
 * `live` the stream reconnects after errors; the server replays history on every connection,
 * so replayed entries are dropped by (timestamp, plugin, stream, message).
 */
export function useExecutionLogs(sessionId: string, executionId: string | null, live = true) {
  const seen = useRef(new Set<string>());
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [bufferExecutionId, setBufferExecutionId] = useState(executionId);

  // Render-phase reset: the buffer belongs to one execution and empties when it changes
  if (bufferExecutionId !== executionId) {
    setBufferExecutionId(executionId);
    setEntries([]);
    seen.current.clear();
  }

  const url =
    sessionId && executionId
      ? api`/api/sessions/${sessionId}/executions/${executionId}/logs/entries`
      : null;

  const onEntry = useCallback((entry: LogEntry) => {
    const key = `${entry.timestamp}|${entry.pluginId}|${entry.stream}|${entry.message}`;
    if (seen.current.has(key)) {
      return;
    }
    seen.current.add(key);
    if (seen.current.size > MAX_LOG_ENTRIES) {
      seen.current.delete(seen.current.values().next().value as string);
    }
    setEntries((previous) => {
      const next = [...previous, entry];
      return next.length > MAX_LOG_ENTRIES
        ? next.slice(next.length - MAX_LOG_ENTRIES)
        : next;
    });
  }, []);

  const { status } = useEventSourceJson<LogEntry>(url, onEntry, live);

  const clear = useCallback(() => setEntries([]), []);

  return {
    entries,
    isStreaming: status === 'open' || status === 'connecting',
    clear,
  };
}
