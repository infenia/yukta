// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQueryClient } from '@tanstack/react-query';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useEventSourceJson } from '@/hooks/useEventSourceJson';
import { fetchApi } from '@/lib/apiClient';
import {
  appendBounded,
  prependHistory,
  type ActivityEvent,
  type Entry,
  type HistoryRow,
} from '../lib/activity';
import { ControlEventsContext, FLUSH_MS } from './ControlEventsContext';

/** Past events asked for at a time. */
const HISTORY_PAGE = 200;

/**
 * One connection to the global event stream for the Control page. Events are collected and added
 * to the list a few times a second (so 50 events/s do not mean 50 renders/s); a ControlOutcome
 * refreshes the panels that show control commands. The newest past events are loaded from
 * `/api/events/history` when the page opens, and older pages on request (`loadOlder`); the server
 * keeps them across a restart in PostgreSQL mode. A reconnect does not backfill the gap.
 */
export function ControlEventsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const pending = useRef<Entry[]>([]);
  const seq = useRef(0);
  const [state, setState] = useState<{ events: Entry[]; dropped: number }>({
    events: [],
    dropped: 0,
  });
  // history entries get negative numbers, falling as they get older, so they sort before live ones
  const histSeq = useRef(-1);
  const oldest = useRef<number | null>(null);
  const loading = useRef(false);
  const [history, setHistory] = useState({ hasMore: false, loading: false });

  const loadOlder = useCallback(() => {
    if (loading.current) return;
    loading.current = true;
    setHistory((h) => ({ ...h, loading: true }));
    const before = oldest.current === null ? '' : `&before=${oldest.current}`;
    fetchApi<HistoryRow[]>(`/api/events/history?limit=${HISTORY_PAGE}${before}`)
      .then((rows) => {
        if (rows.length > 0) oldest.current = rows[rows.length - 1].seq;
        const numbered = rows.map((row) => ({
          ...row.envelope,
          seq: histSeq.current--,
        }));
        setState((previous) => ({
          ...previous,
          events: prependHistory(previous.events, numbered),
        }));
        setHistory({ hasMore: rows.length === HISTORY_PAGE, loading: false });
      })
      .catch(() => setHistory({ hasMore: false, loading: false }))
      .finally(() => {
        loading.current = false;
      });
  }, []);

  useEffect(() => {
    loadOlder();
  }, [loadOlder]);

  const onEvent = useCallback(
    (event: ActivityEvent) => {
      pending.current.push({ ...event, seq: seq.current++ });
      if (
        event.type === 'ControlOutcome' ||
        event.type === 'WorkflowStatusChanged'
      ) {
        void queryClient.invalidateQueries({ queryKey: ['inputs'] });
        void queryClient.invalidateQueries({ queryKey: ['runs'] });
        // slots are taken and freed as runs start and end; limits change by a control command
        void queryClient.invalidateQueries({
          queryKey: ['control', 'scheduler'],
        });
      }
      if (event.type === 'ControlOutcome') {
        void queryClient.invalidateQueries({ queryKey: ['control', 'audit'] });
        void queryClient.invalidateQueries({
          queryKey: ['control', 'supervisors'],
        });
      }
    },
    [queryClient]
  );
  const { status } = useEventSourceJson<ActivityEvent>(
    '/api/events/stream',
    onEvent
  );

  useEffect(() => {
    const timer = setInterval(() => {
      if (pending.current.length === 0) return;
      const added = pending.current;
      pending.current = [];
      setState((previous) => {
        const next = appendBounded(previous.events, added);
        return {
          events: next.buffer,
          dropped: previous.dropped + next.dropped,
        };
      });
    }, FLUSH_MS);
    return () => clearInterval(timer);
  }, []);

  const value = useMemo(
    () => ({
      connected: status === 'open',
      ...state,
      hasMore: history.hasMore,
      loadingOlder: history.loading,
      loadOlder,
    }),
    [status, state, history, loadOlder]
  );
  return (
    <ControlEventsContext.Provider value={value}>
      {children}
    </ControlEventsContext.Provider>
  );
}
